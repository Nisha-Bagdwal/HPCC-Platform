/*##############################################################################

    HPCC SYSTEMS software Copyright (C) 2012 HPCC Systems®.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
############################################################################## */

// Entrypoint for ThorSlave.EXE

#include "platform.h"

#include <stddef.h>
#include <stdlib.h>
#include <assert.h>
#include <stdio.h> 
#include <stdlib.h> 

#include "build-config.h"
#include "jlib.hpp"
#include "jdebug.hpp"
#include "jexcept.hpp"
#include "jfile.hpp"
#include "jmisc.hpp"
#include "jprop.hpp"
#include "jthread.hpp"

#include "thormisc.hpp"
#include "slavmain.hpp"
#include "thorport.hpp"
#include "thexception.hpp"
#include "thmem.hpp"
#include "thbuf.hpp"

#include "mpbase.hpp"
#include "mplog.hpp"
#include "daclient.hpp"
#include "dalienv.hpp"
#include "slave.hpp"
#include "portlist.h"
#include "dafdesc.hpp"
#include "rmtfile.hpp"

#include "slavmain.hpp"

// #define USE_MP_LOG

static INode *masterNode = NULL;
MODULE_INIT(INIT_PRIORITY_STANDARD)
{
    return true;
}
MODULE_EXIT()
{
    ::Release(masterNode);
}

#ifdef _DEBUG
USE_JLIB_ALLOC_HOOK;
#endif

static SocketEndpoint slfEp;
static unsigned mySlaveNum;

static const unsigned defaultStrandBlockSize = 512;
static const unsigned defaultForceNumStrands = 0;

static const char **cmdArgs;

static void replyError(unsigned errorCode, const char *errorMsg)
{
    SocketEndpoint myEp = queryMyNode()->endpoint();
    StringBuffer str("Node '");
    myEp.getUrlStr(str);
    str.append("' exception: ").append(errorMsg);
    Owned<IException> e = MakeStringException(errorCode, "%s", str.str());
    CMessageBuffer msg;
    serializeException(e, msg);
    queryNodeComm().send(msg, 0, MPTAG_THORREGISTRATION);
}

static std::atomic<bool> isRegistered {false};

static bool RegisterSelf(SocketEndpoint &masterEp)
{
    StringBuffer slfStr;
    StringBuffer masterStr;
    LOG(MCdebugProgress, thorJob, "registering %s - master %s",slfEp.getUrlStr(slfStr).str(),masterEp.getUrlStr(masterStr).str());
    try
    {
        SocketEndpoint ep = masterEp;
        ep.port = getFixedPort(getMasterPortBase(), TPORT_mp);
        Owned<INode> masterNode = createINode(ep);
        CMessageBuffer msg;
        msg.append(mySlaveNum);
        queryWorldCommunicator().send(msg, masterNode, MPTAG_THORREGISTRATION);
        if (!queryWorldCommunicator().recv(msg, masterNode, MPTAG_THORREGISTRATION))
            return false;
        PROGLOG("Initialization received");
        unsigned vmajor, vminor;
        msg.read(vmajor);
        msg.read(vminor);
        Owned<IGroup> processGroup = deserializeIGroup(msg);
        mySlaveNum = (unsigned)processGroup->rank(queryMyNode());
        assertex(NotFound != mySlaveNum);
        mySlaveNum++; // 1 based;
        unsigned configSlaveNum = globals->getPropInt("@slavenum", NotFound);
        if (NotFound != configSlaveNum)
            assertex(mySlaveNum == configSlaveNum);

        globals.setown(createPTree(msg));

        /* NB: preserve command line option overrides
         * Not sure if any cmdline options are actually needed by this stage..
         */
        loadArgsIntoConfiguration(globals, cmdArgs);

#ifdef _DEBUG
        unsigned holdSlave = globals->getPropInt("@holdSlave", NotFound);
        if (mySlaveNum == holdSlave)
        {
            DBGLOG("Thor slave %u paused for debugging purposes, attach and set held=false to release", mySlaveNum);
            bool held = true;
            while (held)
                Sleep(5);
        }
#endif
        unsigned channelsPerSlave = globals->getPropInt("@channelsPerSlave", 1);
        unsigned localThorPortInc = globals->getPropInt("@localThorPortInc", DEFAULT_SLAVEPORTINC);
        unsigned slaveBasePort = globals->getPropInt("@slaveport", DEFAULT_THORSLAVEPORT);
        setupCluster(masterNode, processGroup, channelsPerSlave, slaveBasePort, localThorPortInc);

        if (vmajor != THOR_VERSION_MAJOR || vminor != THOR_VERSION_MINOR)
        {
            replyError(TE_FailedToRegisterSlave, "Thor master/slave version mismatch");
            return false;
        }

        ensurePTree(globals, "Debug");
        unsigned numStrands, blockSize;
        if (globals->hasProp("Debug/@forceNumStrands"))
            numStrands = globals->getPropInt("Debug/@forceNumStrands");
        else
        {
            numStrands = defaultForceNumStrands;
            globals->setPropInt("Debug/@forceNumStrands", defaultForceNumStrands);
        }
        if (globals->hasProp("Debug/@strandBlockSize"))
            blockSize = globals->getPropInt("Debug/@strandBlockSize");
        else
        {
            blockSize = defaultStrandBlockSize;
            globals->setPropInt("Debug/@strandBlockSize", defaultStrandBlockSize);
        }
        PROGLOG("Strand defaults: numStrands=%u, blockSize=%u", numStrands, blockSize);

        const char *_masterBuildTag = globals->queryProp("@masterBuildTag");
        const char *masterBuildTag = _masterBuildTag?_masterBuildTag:"no build tag";
        PROGLOG("Master build: %s", masterBuildTag);
        if (!_masterBuildTag || 0 != strcmp(BUILD_TAG, _masterBuildTag))
        {
            StringBuffer errStr("Thor master/slave build mismatch, master = ");
            errStr.append(masterBuildTag).append(", slave = ").append(BUILD_TAG);
            OERRLOG("%s", errStr.str());
#ifndef _DEBUG
            replyError(TE_FailedToRegisterSlave, errStr.str());
            return false;
#endif
        }
        readUnderlyingType<mptag_t>(msg, masterSlaveMpTag);
        readUnderlyingType<mptag_t>(msg, kjServiceMpTag);

        msg.clear();
        if (!queryNodeComm().send(msg, 0, MPTAG_THORREGISTRATION))
            return false;
        PROGLOG("Registration confirmation sent");

        if (!queryNodeComm().recv(msg, 0, MPTAG_THORREGISTRATION))
            return false;
        PROGLOG("Registration confirmation receipt received");

        ::masterNode = LINK(masterNode);

        PROGLOG("verifying mp connection to rest of cluster");
        if (!queryNodeComm().verifyAll())
            OERRLOG("Failed to connect to all nodes");
        else
            PROGLOG("verified mp connection to rest of cluster");
        LOG(MCdebugProgress, thorJob, "registered %s",slfStr.str());
    }
    catch (IException *e)
    {
        FLLOG(MCexception(e), thorJob, e,"slave registration error");
        e->Release();
        return false;
    }
    isRegistered = true;
    return true;
}

static bool jobListenerStopped = true;

bool UnregisterSelf(IException *e)
{
    if (!hasMPServerStarted())
        return false;

    if (!isRegistered)
        return false;

    StringBuffer slfStr;
    slfEp.getUrlStr(slfStr);
    LOG(MCdebugProgress, thorJob, "Unregistering slave : %s", slfStr.str());
    try
    {
        CMessageBuffer msg;
        msg.append(rc_deregister);
        serializeException(e, msg); // NB: allows exception to be NULL
        if (!queryWorldCommunicator().send(msg, masterNode, MPTAG_THORREGISTRATION, 60*1000))
        {
            LOG(MCerror, thorJob, "Failed to unregister slave : %s", slfStr.str());
            return false;
        }
        LOG(MCdebugProgress, thorJob, "Unregistered slave : %s", slfStr.str());
        isRegistered = false;
        return true;
    }
    catch (IException *e) {
        if (!jobListenerStopped)
            FLLOG(MCexception(e), thorJob, e,"slave unregistration error");
        e->Release();
    }
    return false;
}

bool ControlHandler(ahType type)
{
    if (ahInterrupt == type)
        LOG(MCdebugProgress, thorJob, "CTRL-C detected");
    else if (!jobListenerStopped)
        LOG(MCdebugProgress, thorJob, "SIGTERM detected");
    bool unregOK = false;
    if (!jobListenerStopped)
    {
        if (masterNode)
            unregOK = UnregisterSelf(NULL);
        abortSlave();
    }
    return !unregOK;
}

void usage()
{
    printf("usage: thorslave  MASTER=ip:port SLAVE=.:port DALISERVERS=ip:port\n");
    exit(1);
}

#ifdef _WIN32
class CReleaseMutex : public CSimpleInterface, public Mutex
{
public:
    CReleaseMutex(const char *name) : Mutex(name) { }
    ~CReleaseMutex() { if (owner) unlock(); }
}; 
#endif


void startSlaveLog()
{
#ifndef _CONTAINERIZED
    StringBuffer fileName("thorslave");
    Owned<IComponentLogFileCreator> lf = createComponentLogFileCreator(globals->queryProp("@logDir"), "thor");
    StringBuffer slaveNumStr;
    lf->setPostfix(slaveNumStr.append(mySlaveNum).str());
    lf->setCreateAliasFile(false);
    lf->setName(fileName.str());//override default filename
    lf->beginLogging();

    LOG(MCdebugProgress, thorJob, "Opened log file %s", lf->queryLogFileSpec());
#else
    setupContainerizedLogMsgHandler();
#endif
    LOG(MCdebugProgress, thorJob, "Build %s", BUILD_TAG);
}

void setSlaveAffinity(unsigned processOnNode)
{
    const char * affinity = globals->queryProp("@affinity");
    if (affinity)
        setProcessAffinity(affinity);
    else if (globals->getPropBool("@autoAffinity", true))
    {
        const char * nodes = globals->queryProp("@autoNodeAffinityNodes");
        unsigned slavesPerNode = globals->getPropInt("@slavesPerNode", 1);
        setAutoAffinity(processOnNode, slavesPerNode, nodes);
    }

    //The default policy is to allocate from the local node, so restricting allocations to the current sockets
    //may not buy much once the affinity is set up.  It also means it will fail if there is no memory left on
    //this socket - even if there is on others.
    //Therefore it is not recommended unless you have maybe several independent thors running on the same machines
    //with exclusive access to memory.
    if (globals->getPropBool("@numaBindLocal", false))
        bindMemoryToLocalNodes();
}


int main( int argc, const char *argv[]  )
{
    // If using systemd, we will be using daemon code, writing own pid
    for (unsigned i=0;i<(unsigned)argc;i++) {
        if (streq(argv[i],"--daemon") || streq(argv[i],"-d")) {
            if (daemon(1,0) || write_pidfile(argv[++i])) {
                perror("Failed to daemonize");
                return EXIT_FAILURE;
            }
            break;
        }
    }
#if defined(WIN32) && defined(_DEBUG)
    int tmpFlag = _CrtSetDbgFlag( _CRTDBG_REPORT_FLAG );
    tmpFlag |= _CRTDBG_LEAK_CHECK_DF;
    _CrtSetDbgFlag( tmpFlag );
#endif

    InitModuleObjects();

    addAbortHandler(ControlHandler);
    EnableSEHtoExceptionMapping();

    dummyProc();
#ifndef __64BIT__
    // Restrict stack sizes on 32-bit systems
    Thread::setDefaultStackSize(0x10000);   // NB under windows requires linker setting (/stack:)
#endif

#ifdef _WIN32
    Owned<CReleaseMutex> globalNamedMutex;
#endif 

    globals.setown(createPTree("Thor"));
    unsigned multiThorMemoryThreshold = 0;

    Owned<IException> unregisterException;
    try
    {
        if (argc==1)
        {
            usage();
            return 1;
        }
        cmdArgs = argv+1;
#ifdef _CONTAINERIZED
        globals.setown(loadConfiguration(thorDefaultConfigYaml, argv, "thor", "THOR", nullptr, nullptr));
#else
        loadArgsIntoConfiguration(globals, cmdArgs);
#endif

        const char *master = globals->queryProp("@master");
        if (!master)
            usage();

        mySlaveNum = globals->getPropInt("@slavenum", NotFound);
        /* NB: in cloud/non-local storage mode, slave number is not known until after registration with the master
        * For the time being log file names are based on their slave number, so can only start when known.
        */
        bool loggingStarted = false;
        if (NotFound != mySlaveNum)
        {
            startSlaveLog();
            loggingStarted = true;
        }

        // In container world, SLAVE= will not be used
        const char *slave = globals->queryProp("@slave");
        if (slave)
        {
            slfEp.set(slave);
            localHostToNIC(slfEp);
        }
        else 
            slfEp.setLocalHost(0);

        // TBD: use new config/init system for generic handling of init settings vs command line overrides
        if (0 == slfEp.port) // assume default from config if not on command line
            slfEp.port = globals->getPropInt("@slaveport", THOR_BASESLAVE_PORT);

        startMPServer(DCR_ThorSlave, slfEp.port, false);
        if (0 == slfEp.port)
            slfEp.port = queryMyNode()->endpoint().port;
        setMachinePortBase(slfEp.port);

        setSlaveAffinity(globals->getPropInt("@slaveprocessnum"));

        if (globals->getPropBool("@MPChannelReconnect"))
            getMPServer()->setOpt(mpsopt_channelreopen, "true");
#ifdef USE_MP_LOG
        startLogMsgParentReceiver();
        LOG(MCdebugProgress, thorJob, "MPServer started on port %d", getFixedPort(TPORT_mp));
#endif

        SocketEndpoint masterEp(master);
        localHostToNIC(masterEp);
        setMasterPortBase(masterEp.port);
        markNodeCentral(masterEp);

        if (RegisterSelf(masterEp))
        {
            if (!loggingStarted)
                startSlaveLog();

            if (globals->getPropBool("Debug/@slaveDaliClient"))
                enableThorSlaveAsDaliClient();

            IDaFileSrvHook *daFileSrvHook = queryDaFileSrvHook();
            if (daFileSrvHook) // probably always installed
                daFileSrvHook->addFilters(globals->queryPropTree("NAS"), &slfEp);

            enableForceRemoteReads(); // forces file reads to be remote reads if they match environment setting 'forceRemotePattern' pattern.

            StringBuffer thorPath;
            globals->getProp("@thorPath", thorPath);
            recursiveCreateDirectory(thorPath.str());
            int err = _chdir(thorPath.str());
            if (err)
            {
                IException *e = makeErrnoExceptionV(-1, "Failed to change dir to '%s'", thorPath.str());
                FLLOG(MCexception(e), thorJob, e);
                throw e;
            }

// Initialization from globals
            setIORetryCount(globals->getPropInt("Debug/@ioRetries")); // default == 0 == off

            StringBuffer str;
            if (globals->getProp("@externalProgDir", str.clear()))
                _mkdir(str.str());
            else
                globals->setProp("@externalProgDir", thorPath);

            const char * overrideBaseDirectory = globals->queryProp("@thorDataDirectory");
            const char * overrideReplicateDirectory = globals->queryProp("@thorReplicateDirectory");
            StringBuffer datadir;
            StringBuffer repdir;
            if (getConfigurationDirectory(globals->queryPropTree("Directories"),"data","thor",globals->queryProp("@name"),datadir))
                overrideBaseDirectory = datadir.str();
            if (getConfigurationDirectory(globals->queryPropTree("Directories"),"mirror","thor",globals->queryProp("@name"),repdir))
                overrideReplicateDirectory = repdir.str();
            if (!isEmptyString(overrideBaseDirectory))
                setBaseDirectory(overrideBaseDirectory, false);
            if (!isEmptyString(overrideReplicateDirectory))
                setBaseDirectory(overrideReplicateDirectory, true);
            StringBuffer tempDirStr;
            if (getConfigurationDirectory(globals->queryPropTree("Directories"),"temp","thor",globals->queryProp("@name"), tempDirStr))
                globals->setProp("@thorTempDirectory", tempDirStr.str());
            else
                tempDirStr.append(globals->queryProp("@thorTempDirectory"));
            addPathSepChar(tempDirStr).append(mySlaveNum);

            logDiskSpace(); // Log before temp space is cleared
            SetTempDir(mySlaveNum, tempDirStr.str(), "thtmp", true);

            useMemoryMappedRead(globals->getPropBool("@useMemoryMappedRead"));

            LOG(MCdebugProgress, thorJob, "ThorSlave Version LCR - %d.%d started",THOR_VERSION_MAJOR,THOR_VERSION_MINOR);
            StringBuffer url;
            LOG(MCdebugProgress, thorJob, "Slave %s - temporary dir set to : %s", slfEp.getUrlStr(url).str(), queryTempDir());
#ifdef _WIN32
            ULARGE_INTEGER userfree;
            ULARGE_INTEGER total;
            ULARGE_INTEGER free;
            if (GetDiskFreeSpaceEx("c:\\",&userfree,&total,&free)&&total.QuadPart) {
                unsigned pc = (unsigned)(free.QuadPart*100/total.QuadPart);
                LOG(MCdebugProgress, thorJob, "Total disk space = %" I64F "d k", total.QuadPart/1000);
                LOG(MCdebugProgress, thorJob, "Free  disk space = %" I64F "d k", free.QuadPart/1000);
                LOG(MCdebugProgress, thorJob, "%d%% disk free\n",pc);
            }
#endif
            if (getConfigurationDirectory(globals->queryPropTree("Directories"),"query","thor",globals->queryProp("@name"),str.clear()))
                globals->setProp("@query_so_dir", str.str());
            else
                globals->getProp("@query_so_dir", str.clear());
            if (str.length())
            {
                if (globals->getPropBool("Debug/@dllsToSlaves", true))
                {
                    StringBuffer uniqSoPath;
                    if (PATHSEPCHAR == str.charAt(str.length()-1))
                        uniqSoPath.append(str.length()-1, str.str());
                    else
                        uniqSoPath.append(str);
                    uniqSoPath.append("_").append(getMachinePortBase());
                    str.swapWith(uniqSoPath);
                    globals->setProp("@query_so_dir", str.str());
                }
                PROGLOG("Using querySo directory: %s", str.str());
                recursiveCreateDirectory(str.str());
            }
     
            multiThorMemoryThreshold = globals->getPropInt("@multiThorMemoryThreshold")*0x100000;
            if (multiThorMemoryThreshold) {
                StringBuffer lgname;
                if (!globals->getProp("@multiThorResourceGroup",lgname))
                    globals->getProp("@nodeGroup",lgname);
                if (lgname.length()) {
                    Owned<ILargeMemLimitNotify> notify = createMultiThorResourceMutex(lgname.str());
                    setMultiThorMemoryNotify(multiThorMemoryThreshold,notify);
                    PROGLOG("Multi-Thor resource limit for %s set to %" I64F "d",lgname.str(),(__int64)multiThorMemoryThreshold);
                }   
                else
                    multiThorMemoryThreshold = 0;
            }

            unsigned pinterval = globals->getPropInt("@system_monitor_interval",1000*60);
            if (pinterval)
                startPerformanceMonitor(pinterval, PerfMonStandard, nullptr);

            slaveMain(jobListenerStopped);
        }

        LOG(MCdebugProgress, thorJob, "ThorSlave terminated OK");
    }
    catch (IException *e) 
    {
        if (!jobListenerStopped)
            FLLOG(MCexception(e), thorJob, e,"ThorSlave");
        unregisterException.setown(e);
    }
    stopPerformanceMonitor();
    ClearTempDirs();

    if (multiThorMemoryThreshold)
        setMultiThorMemoryNotify(0,NULL);
    roxiemem::releaseRoxieHeap();

    if (unregisterException.get())
        UnregisterSelf(unregisterException);

    if (globals->getPropBool("Debug/@slaveDaliClient"))
        disableThorSlaveAsDaliClient();

#ifdef USE_MP_LOG
    stopLogMsgReceivers();
#endif
    stopMPServer();
    releaseAtoms(); // don't know why we can't use a module_exit to destruct these...

    ExitModuleObjects(); // not necessary, atexit will call, but good for leak checking
    return 0;
}

