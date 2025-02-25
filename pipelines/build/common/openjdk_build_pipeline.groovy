/*
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

import common.IndividualBuildConfig
import common.MetaData
import common.VersionInfo
import common.RepoHandler
import groovy.json.*
import java.nio.file.NoSuchFileException
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

import java.util.regex.Matcher

/**
 * This file is a template for running a build for a given configuration
 * A configuration is for example jdk10u-mac-x64-temurin.
 *
 * This file is referenced by the pipeline template create_job_from_template.groovy
 *
 * A pipeline looks like:
 *  1. Check out and build JDK by calling build-farm/make-adopt-build-farm.sh
 *  2. Archive artifacts created by build
 *  3. Run all tests defined in the configuration
 *  4. Sign artifacts if needed and re-archive
 *
 */

/*
    Extracts the named regex element `groupName` from the `matched` regex matcher and adds it to `map.name`
    If it is not present add `0`
 */

class Build {

    final IndividualBuildConfig buildConfig
    final Map USER_REMOTE_CONFIGS
    final Map DEFAULTS_JSON
    final Map ADOPT_DEFAULTS_JSON
    final context
    final env
    final currentBuild

    VersionInfo versionInfo = null
    String scmRef = ''
    String fullVersionOutput = ''
    String makejdkArgs = ''
    String configureArguments = ''
    String makeCommandArgs = ''
    String j9Major = ''
    String j9Minor = ''
    String j9Security = ''
    String j9Tags = ''
    String vendorName = ''
    String buildSource = ''
    String openjdkSource = ''
    String openjdk_built_config = ''
    String dockerImageDigest = ''
    Map<String,String> dependency_version = new HashMap<String,String>()
    String crossCompileVersionPath = ''
    String jdkUrl = ''
    String jreUrl = ''
    String testimageUrl = ''
    String artifactoryCredential = ''
    String artifactoryBaseUrl = ''
    Map variantVersion = [:]

    // Declare timeouts for each critical stage (unit is HOURS)
    Map buildTimeouts = [
        API_REQUEST_TIMEOUT : 1,
        NODE_CLEAN_TIMEOUT : 1,
        NODE_CHECKOUT_TIMEOUT : 1,
        BUILD_JDK_TIMEOUT : 8,
        BUILD_ARCHIVE_TIMEOUT : 3,
        CONTROLLER_CLEAN_TIMEOUT : 1,
        DOCKER_CHECKOUT_TIMEOUT : 1,
        DOCKER_PULL_TIMEOUT : 2,
        ARCHIVE_ARTIFACTS_TIMEOUT : 6
    ]

    /*
    Constructor
    */
    Build(
        IndividualBuildConfig buildConfig,
        Map USER_REMOTE_CONFIGS,
        Map DEFAULTS_JSON,
        Map ADOPT_DEFAULTS_JSON,
        def context,
        def env,
        def currentBuild
    ) {
        this.buildConfig = buildConfig
        this.USER_REMOTE_CONFIGS = USER_REMOTE_CONFIGS
        this.DEFAULTS_JSON = DEFAULTS_JSON
        this.ADOPT_DEFAULTS_JSON = ADOPT_DEFAULTS_JSON
        this.context = context
        this.currentBuild = currentBuild
        this.env = env
    }

    /*
    Returns the java version number for this job (e.g. 8, 11, 17, ...)
    */
    Integer getJavaVersionNumber() {
        def javaToBuild = buildConfig.JAVA_TO_BUILD
        // version should be something like "jdk8u" or "jdk" for HEAD
        Matcher matcher = javaToBuild =~ /.*?(?<version>\d+).*?/
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group('version'))
        } else if ('jdk'.equalsIgnoreCase(javaToBuild.trim())) {
            int headVersion
            try {
                context.timeout(time: buildTimeouts.API_REQUEST_TIMEOUT, unit: 'HOURS') {
                    // Query the Adopt api to get the "tip_version"
                    String helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
                    def JobHelper = context.library(identifier: "openjdk-jenkins-helper@${helperRef}").JobHelper
                    context.println 'Querying Adopt Api for the JDK-Head number (tip_version)...'

                    def response = JobHelper.getAvailableReleases(context)
                    headVersion = (int) response[('tip_version')]
                    context.println "Found Java Version Number: ${headVersion}"
                }
            } catch (FlowInterruptedException e) {
                throw new Exception("[ERROR] Adopt API Request timeout (${buildTimeouts.API_REQUEST_TIMEOUT} HOURS) has been reached. Exiting...")
            }
            return headVersion
        } else {
            throw new Exception("Failed to read java version '${javaToBuild}'")
        }
    }

    /*
    Calculates which test job we should execute for each requested test type.
    The test jobs all follow the same name naming pattern that is defined in the aqa-tests repository.
    E.g. Test_openjdk11_hs_sanity.system_ppc64_aix
    */
    def getSmokeTestJobParams() {
        def jobParams = getCommonTestJobParams()
        jobParams.put('LEVELS', 'extended')
        jobParams.put('GROUPS', 'functional')
        jobParams.put('TEST_JOB_NAME', "${env.JOB_NAME}_SmokeTests")
        jobParams.put('BUILD_LIST', 'functional/buildAndPackage')
        def vendorTestRepos = ((String)ADOPT_DEFAULTS_JSON['repository']['build_url']) - ('.git')
        def vendorTestDirs = ADOPT_DEFAULTS_JSON['repository']['test_dirs']
        def vendorTestBranches = ADOPT_DEFAULTS_JSON['repository']['build_branch']
        jobParams.put('VENDOR_TEST_REPOS', vendorTestRepos)
        jobParams.put('VENDOR_TEST_DIRS', vendorTestDirs)
        jobParams.put('VENDOR_TEST_BRANCHES', vendorTestBranches)
        return jobParams
    }

    def getAQATestJobParams(testType) {
        def jobParams = getCommonTestJobParams()
        def (level, group) = testType.tokenize('.')
        jobParams.put('LEVELS', level)
        jobParams.put('GROUPS', group)
        def variant
        switch (buildConfig.VARIANT) {
            case 'openj9': variant = 'j9'; break
            case 'corretto': variant = 'corretto'; break
            case 'dragonwell': variant = 'dragonwell'; break;
            case 'fast_startup': variant = 'fast_startup'; break;
            case 'bisheng': variant = 'bisheng'; break;
            default: variant = 'hs'
        }
        def jobName = "Test_openjdk${jobParams['JDK_VERSIONS']}_${variant}_${testType}_${jobParams['ARCH_OS_LIST']}"

        jobParams.put('TEST_JOB_NAME', jobName)
        return jobParams
    }

    def getCommonTestJobParams() {
        def jobParams = [:]
        String jdk_Version = getJavaVersionNumber() as String
        jobParams.put('JDK_VERSIONS', jdk_Version)

        if (buildConfig.VARIANT == 'temurin') {
            jobParams.put('JDK_IMPL', 'hotspot')
        } else {
            jobParams.put('JDK_IMPL', buildConfig.VARIANT)
        }

        def arch = buildConfig.ARCHITECTURE
        if (arch == 'x64') {
            arch = 'x86-64'
        }

        // Default to a 25 hours for all test jobs
        jobParams.put('TIME_LIMIT', '25')

        def arch_os = "${arch}_${buildConfig.TARGET_OS}"
        jobParams.put('ARCH_OS_LIST', arch_os)
        jobParams.put('LIGHT_WEIGHT_CHECKOUT', true)
        return jobParams
    }
    /*
    Retrieve the corresponding OpenJDK source code repository branch. This is used the downstream tests to determine what source code branch the tests should run against.
    */
    private getJDKBranch() {
        def jdkBranch

        if (buildConfig.SCM_REF) {
            // We need to override the SCM ref on jdk8 arm builds change aarch64-shenandoah-jdk8u282-b08 to jdk8u282-b08
            if (buildConfig.JAVA_TO_BUILD == 'jdk8u' &&  buildConfig.VARIANT == 'temurin' && (buildConfig.ARCHITECTURE == 'aarch64' || buildConfig.ARCHITECTURE == 'arm')) {
                jdkBranch = buildConfig.OVERRIDE_FILE_NAME_VERSION
            } else {
                jdkBranch = buildConfig.SCM_REF
            }
        } else {
            if (buildConfig.VARIANT == 'corretto') {
                jdkBranch = 'develop'
            } else if (buildConfig.VARIANT == 'openj9') {
                jdkBranch = 'openj9'
            } else if (buildConfig.VARIANT == 'hotspot') {
                jdkBranch = 'master'
            } else if (buildConfig.VARIANT == 'temurin') {
                jdkBranch = 'dev'
            } else if (buildConfig.VARIANT == 'dragonwell') {
                jdkBranch = 'master'
            } else if (buildConfig.VARIANT == 'fast_startup') {
                jdkBranch = 'master'
            } else if (buildConfig.VARIANT == 'bisheng') {
                jdkBranch = 'master'
            } else {
                throw new Exception("Unrecognised build variant: ${buildConfig.VARIANT} ")
            }
        }

        return jdkBranch
    }

    /*
    Retrieve the corresponding OpenJDK source code repository. This is used the downstream tests to determine what source code the tests should run against.
    */
    private getJDKRepo() {
        def jdkRepo
        def suffix
        def javaNumber = getJavaVersionNumber()

        switch(buildConfig.VARIANT) {
            case 'corretto':
                suffix = "corretto/corretto-${javaNumber}"
                break
            case 'openj9':
                def openj9JavaToBuild = buildConfig.JAVA_TO_BUILD
                if (openj9JavaToBuild.endsWith('u')) {
                    // OpenJ9 extensions repo does not use the "u" suffix
                    openj9JavaToBuild = openj9JavaToBuild.substring(0, openj9JavaToBuild.length() - 1)
                }
                suffix = "ibmruntimes/openj9-openjdk-${openj9JavaToBuild}"
                break
            case 'temurin':
                if (buildConfig.ARCHITECTURE == 'arm' && buildConfig.JAVA_TO_BUILD == 'jdk8u') {
                    suffix = 'adoptium/aarch32-jdk8u'
                } else if (buildConfig.TARGET_OS == 'alpine-linux' && buildConfig.JAVA_TO_BUILD == 'jdk8u') {
                    suffix = 'adoptium/alpine-jdk8u'
                } else if (buildConfig.ARCHITECTURE == 'riscv64' && buildConfig.JAVA_TO_BUILD == 'jdk11u') {
                    suffix = 'adoptium/riscv-port-jdk11u'
                } else {
                    suffix = "adoptium/${buildConfig.JAVA_TO_BUILD}"
                }
                break
            case 'hotspot':
                if (buildConfig.ARCHITECTURE == "riscv64"
                     && (buildConfig.JAVA_TO_BUILD == "jdk8u"
                        || buildConfig.JAVA_TO_BUILD == "jdk11u")) {
                    suffix = "openjdk/riscv-port-${buildConfig.JAVA_TO_BUILD}";
                } else {
                    suffix = "openjdk/${buildConfig.JAVA_TO_BUILD}"
                }
                break
            case 'dragonwell':
                suffix = "alibaba/dragonwell${javaNumber}"
                break
            case 'fast_startup':
                suffix = 'adoptium/jdk11u-fast-startup-incubator'
                break
            case 'bisheng':
                suffix = "openeuler-mirror/bishengjdk-${javaNumber}"
                break
            default:
                throw new Exception("Unrecognised build variant: ${buildConfig.VARIANT} ")
        }

        jdkRepo = "https://github.com/${suffix}"
        if (buildConfig.BUILD_ARGS.count('--ssh') > 0) {
            jdkRepo = "git@github.com:${suffix}"
        }

        return jdkRepo
    }
    /*
    Run smoke tests, which should block the running of downstream test jobs if there are failures.
    If a test job that doesn't exist, it will be created dynamically.
    */
    def runSmokeTests() {
        def additionalTestLabel = buildConfig.ADDITIONAL_TEST_LABEL
        def useAdoptShellScripts = Boolean.valueOf(buildConfig.USE_ADOPT_SHELL_SCRIPTS)
        def vendorTestBranches = useAdoptShellScripts ? ADOPT_DEFAULTS_JSON['repository']['build_branch'] : DEFAULTS_JSON['repository']['build_branch']
        def vendorTestRepos = useAdoptShellScripts ? ADOPT_DEFAULTS_JSON['repository']['build_url'] :  DEFAULTS_JSON['repository']['build_url']
        vendorTestRepos = vendorTestRepos - ('.git')

        // Use BUILD_REF override if specified
        vendorTestBranches = buildConfig.BUILD_REF ?: vendorTestBranches

        try {
            context.println 'Running smoke test'
            context.stage('smoke test') {
                def jobParams = getSmokeTestJobParams()
                def jobName = jobParams.TEST_JOB_NAME
                String helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
                def JobHelper = context.library(identifier: "openjdk-jenkins-helper@${helperRef}").JobHelper
                if (!JobHelper.jobIsRunnable(jobName as String)) {
                    context.node('worker') {
                        context.sh('curl -Os https://raw.githubusercontent.com/adoptium/aqa-tests/master/buildenv/jenkins/testJobTemplate')
                        def templatePath = 'testJobTemplate'
                        context.println "Smoke test job doesn't exist, create test job: ${jobName}"
                        context.jobDsl targets: templatePath, ignoreExisting: false, additionalParameters: jobParams
                    }
                }

                def testJob = context.build job: jobName,
                    propagate: false,
                    parameters: [
                            context.string(name: 'SDK_RESOURCE', value: 'customized'),
                            context.string(name: 'CUSTOMIZED_SDK_URL', value: jdkUrl),
                            context.string(name: 'CUSTOMIZED_SDK_URL_CREDENTIAL_ID', value: artifactoryCredential),
                            context.string(name: 'JDK_VERSION', value: "${jobParams.JDK_VERSIONS}"),
                            context.string(name: 'LABEL_ADDITION', value: additionalTestLabel),
                            context.booleanParam(name: 'KEEP_REPORTDIR', value: buildConfig.KEEP_TEST_REPORTDIR),
                            context.string(name: 'ACTIVE_NODE_TIMEOUT', value: "${buildConfig.ACTIVE_NODE_TIMEOUT}"),
                            context.booleanParam(name: 'DYNAMIC_COMPILE', value: true),
                            context.string(name: 'VENDOR_TEST_REPOS', value: vendorTestRepos),
                            context.string(name: 'VENDOR_TEST_BRANCHES', value: vendorTestBranches),
                            context.string(name: 'TIME_LIMIT', value: '1')
                    ]  
                currentBuild.result = testJob.getResult()
                return testJob.getResult()
            }
        } catch (Exception e) {
            context.println "Failed to execute test: ${e.message}"
            throw new Exception('[ERROR] Smoke Tests failed indicating a problem with the build artifact. No further tests will run until Smoke test failures are fixed. ')
        }
    }
    /*
    Run the downstream test jobs based off the configuration passed down from the top level pipeline jobs.
    If a test job doesn't exist, it will be created dynamically.
    */
    def runAQATests() {
        def testStages = [:]
        def jdkBranch = getJDKBranch()
        def jdkRepo = getJDKRepo()
        def openj9Branch = (buildConfig.SCM_REF && buildConfig.VARIANT == 'openj9') ? buildConfig.SCM_REF : 'master'

        def vendorTestRepos = ''
        def vendorTestBranches = ''
        def vendorTestDirs = ''
        List testList = buildConfig.TEST_LIST
        List dynamicList = buildConfig.DYNAMIC_LIST
        List numMachines = buildConfig.NUM_MACHINES
        def enableTestDynamicParallel = Boolean.valueOf(buildConfig.ENABLE_TESTDYNAMICPARALLEL)
        def aqaBranch = 'master'
        def useTestEnvProperties = false
        if (buildConfig.SCM_REF && buildConfig.AQA_REF) {
            aqaBranch = buildConfig.AQA_REF
            useTestEnvProperties = true
        }

        def aqaAutoGen = buildConfig.AQA_AUTO_GEN ?: false
        testList.each { testType ->
            // For each requested test, i.e 'sanity.openjdk', 'sanity.system', 'sanity.perf', 'sanity.external', call test job
            def customizedSdkUrl = jdkUrl
            try {
                testStages["${testType}"] = {
                    context.println "Running test: ${testType}"
                    context.stage("${testType}") {
                        def isFipsTestBuild = false
                        def rerunIterations = '3'
                        if ("${testType}".contains(".fips140_2")) {
                            testType = testType.replace(".fips140_2","")
                            isFipsTestBuild = true
                            rerunIterations = '0'
                        }
                        def keep_test_reportdir = buildConfig.KEEP_TEST_REPORTDIR
                        if ("${testType}".contains('dev') || "${testType}".contains('external')) {
                            rerunIterations = '0'
                        }
                        if (("${testType}".contains('openjdk')) || ("${testType}".contains('jck')) || (testType  == 'dev.functional')) {
                            // Keep test reportdir always for JUnit targets
                            keep_test_reportdir = true
                            if (("${testType}".contains('special'))) {
                                customizedSdkUrl += " " + jreUrl
                            }
                            customizedSdkUrl += " " + testimageUrl
                        } else if ("${testType}".contains('jck')) {
                            // Keep test reportdir always for JUnit targets
                            keep_test_reportdir = true
                        }

                        if ("${testType}".contains('dev')) {
                            rerunIterations = '0'
                        }

                        def DYNAMIC_COMPILE = false
                        if (("${testType}".contains('functional')) || ("${testType}".contains('external'))) {
                            DYNAMIC_COMPILE = true
                        }

                        def VENDOR_TEST_REPOS = ''
                        def VENDOR_TEST_BRANCHES = ''
                        def VENDOR_TEST_DIRS = ''
                        if ("${testType}".contains('functional')) {
                            VENDOR_TEST_REPOS = 'git@github.ibm.com:runtimes/test.git'
                            VENDOR_TEST_BRANCHES = aqaBranch
                            VENDOR_TEST_DIRS = 'functional'
                            customizedSdkUrl += " " + testimageUrl
                        } else if ("${testType}".contains('jck')) {
                            VENDOR_TEST_REPOS = 'git@github.ibm.com:runtimes/jck.git'
                            VENDOR_TEST_BRANCHES = "main"
                            VENDOR_TEST_DIRS = 'jck'
                        } else if ("${testType}".contains('special.openjdk')) {
                            VENDOR_TEST_REPOS = 'git@github.ibm.com:runtimes/osb-tests.git'
                            VENDOR_TEST_BRANCHES = "ibm_tlda"
                            VENDOR_TEST_DIRS = 'openjdk'
                        }

                        def DOCKER_REGISTRY_URL = ''
                        def DOCKER_REGISTRY_URL_CREDENTIAL_ID = ''
                        def BASE_DOCKER_REGISTRY_CREDENTIAL_ID = ''
                        if ("${testType}".contains('external')) {
                            DOCKER_REGISTRY_URL = 'docker-na.artifactory.swg-devops.com/sys-rt-docker-local'
                            DOCKER_REGISTRY_URL_CREDENTIAL_ID = artifactoryCredential
                            rerunIterations = '0'
                            if (buildConfig.DOCKER_CREDENTIAL) {
                                BASE_DOCKER_REGISTRY_CREDENTIAL_ID = buildConfig.DOCKER_CREDENTIAL
                            }
                        }

                        def additionalTestLabel = buildConfig.ADDITIONAL_TEST_LABEL
                        def relatedNodeLabel = ''
                        def additionalArtifactsRequired = ''
                        if (testType  == 'dev.openjdk') {
                            context.println "${testType} need extra label sw.tool.docker"
                            if (additionalTestLabel == '') {
                                additionalTestLabel = 'sw.tool.docker'
                            } else {
                                additionalTestLabel += '&&sw.tool.docker'
                            }
                        } else if (testType  == 'dev.jck') {
                            if (buildConfig.TARGET_OS == "aix") {
	                            if (additionalTestLabel != '') {
	                                additionalTestLabel += '&&'
	                            }  
	                            additionalTestLabel += 'ci.geo.rtp&&sw.tool.jckshare'
	                            relatedNodeLabel = 'ci.geo.rtp&&sw.tool.jckrefserver'
	                        }
	                        additionalArtifactsRequired = 'RI_JDK'
                        }

                        if (testType  == 'dev.system') {
                            def useAdoptShellScripts = Boolean.valueOf(buildConfig.USE_ADOPT_SHELL_SCRIPTS)
                            vendorTestBranches = useAdoptShellScripts ? ADOPT_DEFAULTS_JSON['repository']['build_branch'] : DEFAULTS_JSON['repository']['build_branch']
                            vendorTestRepos = useAdoptShellScripts ? ADOPT_DEFAULTS_JSON['repository']['build_url'] :  DEFAULTS_JSON['repository']['build_url']
                            vendorTestRepos = vendorTestRepos - ('.git')
                            vendorTestDirs = '/test/system'
                            // Use BUILD_REF override if specified
                            vendorTestBranches = buildConfig.BUILD_REF ?: vendorTestBranches
                        }

                        def jobParams = getAQATestJobParams(testType)

                        def testFlag = ''
                        if (isFipsTestBuild) {
                            jobParams.put('TEST_JOB_NAME', "${jobParams.TEST_JOB_NAME}_fips140_2")
                            testFlag = 'FIPS140_2'
                        }
                        def parallel = 'None'
                        def numMachinesPerTest = ''

                        if (enableTestDynamicParallel && dynamicList.contains(testType)) {
                            numMachinesPerTest = numMachines[(dynamicList.indexOf(testType))]
                            if (!numMachinesPerTest) {
                                // see build configuration in jdk*_pipeline_config.groovy
                                // when numMachines is an array, its size should match the testLists size
                                throw new Exception("No number of machines provided for running ${testType} tests in parallel, numMachines: ${numMachines}!")
                            }
                            context.println "Number of machines for running parallel tests: ${numMachinesPerTest}"

                            parallel = 'Dynamic'
                        }

                        def jobName = jobParams.TEST_JOB_NAME
                        String helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
                        def JobHelper = context.library(identifier: "openjdk-jenkins-helper@${helperRef}").JobHelper

                        // Create test job if AQA_AUTO_GEN is set to true, the job doesn't exist or is not runnable
                        if (aqaAutoGen || !JobHelper.jobIsRunnable(jobName as String)) {
                            // use Test_Job_Auto_Gen if it is runnable. Otherwise, use testJobTemplate from aqa-tests repo
                            if (JobHelper.jobIsRunnable('Test_Job_Auto_Gen')) {
                                def updatedParams = []
                                // loop through all the params and set string and boolean accordingly
                                jobParams.each { param ->
                                    def value = param.value.toString()
                                    if (value == 'true' || value == 'false') {
                                        updatedParams << context.booleanParam(name: param.key, value: value.toBoolean())
                                    } else {
                                        updatedParams << context.string(name: param.key, value: value)
                                    }
                                }
                                context.println "Use Test_Job_Auto_Gen to generate AQA test job with parameters: ${updatedParams}"
                                context.catchError {
                                    context.build job: 'Test_Job_Auto_Gen', propagate: false, parameters: updatedParams
                                }
                            } else {
                                context.node('worker') {
                                    context.sh('curl -Os https://raw.githubusercontent.com/adoptium/aqa-tests/master/buildenv/jenkins/testJobTemplate')
                                    def templatePath = 'testJobTemplate'
                                    if (!JobHelper.jobIsRunnable(jobName as String)) {
                                        context.println "AQA test job: ${jobName} doesn't exist, use testJobTemplate to generate job : ${jobName}"
                                    } else {
                                        context.println "Use testJobTemplate to regenerate job: ${jobName}, note: default job parameters may change."
                                    }
                                    context.jobDsl targets: templatePath, ignoreExisting: false, additionalParameters: jobParams
                                }
                            }
                        }

                    def testJobParams = [
                        context.string(name: 'SDK_RESOURCE', value: 'customized'),
                        context.string(name: 'CUSTOMIZED_SDK_URL', value: customizedSdkUrl),
                        context.string(name: 'CUSTOMIZED_SDK_URL_CREDENTIAL_ID', value: artifactoryCredential),
                        context.string(name: 'RELEASE_TAG', value: "${buildConfig.SCM_REF}"),
	                    context.string(name: 'JDK_REPO', value: jdkRepo),
	                    context.string(name: 'JDK_BRANCH', value: jdkBranch),
	                    context.string(name: 'OPENJ9_BRANCH', value: openj9Branch),
	                    context.string(name: 'LABEL_ADDITION', value: additionalTestLabel),
	                    context.booleanParam(name: 'KEEP_REPORTDIR', value: keep_test_reportdir),
	                    context.string(name: 'PARALLEL', value: parallel),
	                    context.string(name: 'NUM_MACHINES', value: "${numMachinesPerTest}"),
	                    context.booleanParam(name: 'USE_TESTENV_PROPERTIES', value: useTestEnvProperties),
	                    context.booleanParam(name: 'GENERATE_JOBS', value: aqaAutoGen),
	                    context.string(name: 'ADOPTOPENJDK_BRANCH', value: aqaBranch),
                        context.string(name: 'TEST_FLAG', value: "${testFlag}"),
	                    context.string(name: 'ACTIVE_NODE_TIMEOUT', value: "${buildConfig.ACTIVE_NODE_TIMEOUT}"),
	                    context.booleanParam(name: 'DYNAMIC_COMPILE', value: DYNAMIC_COMPILE),
	                    context.string(name: 'DOCKER_REGISTRY_URL', value: DOCKER_REGISTRY_URL),
                        context.string(name: 'DOCKER_REGISTRY_URL_CREDENTIAL_ID', value: DOCKER_REGISTRY_URL_CREDENTIAL_ID),
                        context.string(name: 'BASE_DOCKER_REGISTRY_CREDENTIAL_ID', value: BASE_DOCKER_REGISTRY_CREDENTIAL_ID),
                        context.string(name: 'VENDOR_TEST_REPOS', value: VENDOR_TEST_REPOS),
                        context.string(name: 'VENDOR_TEST_BRANCHES', value: VENDOR_TEST_BRANCHES),
                        context.string(name: 'VENDOR_TEST_DIRS', value: VENDOR_TEST_DIRS),
                        context.string(name: 'RERUN_ITERATIONS', value: "${rerunIterations}"),
                        context.string(name: 'RELATED_NODES', value: relatedNodeLabel), 
                        context.string(name: 'ADDITIONAL_ARTIFACTS_REQUIRED', value: additionalArtifactsRequired)
                        ]

                        // If TIME_LIMIT is set, override target job default TIME_LIMIT value.
                        if (jobParams.any{mapEntry -> mapEntry.key.equals("TIME_LIMIT")}) {
                            testJobParams.add(context.string(name: 'TIME_LIMIT', value: jobParams["TIME_LIMIT"]))
                        }

                        def testJob = context.build job: jobName,
                                        propagate: false,
                                        parameters: testJobParams,
                                        wait: true
                        currentBuild.result = testJob.getResult()
                        context.node('worker') {
                            //Copy Taps files from downstream test jobs if files available. 
                            context.sh 'rm -f workspace/target/AQAvitTaps/*.tap'
                            try {
                                context.timeout(time: 2, unit: 'HOURS') {
                                    context.copyArtifacts(
                                        projectName:jobName,
                                        selector:context.specific("${testJob.getNumber()}"),
                                        filter: "**/${jobName}*.tap",
                                        target: 'workspace/target/AQAvitTaps/',
                                        fingerprintArtifacts: true,
                                        flatten: true
                                    )
                                }
                            } catch (Exception e) {
                                context.echo "Cannot run copyArtifacts from job ${jobName}. Exception: ${e.message}. Skipping copyArtifacts..."
                            }
                            context.archiveArtifacts allowEmptyArchive: true, artifacts: 'workspace/target/AQAvitTaps/*.tap', fingerprint: true
                        }
                    }
                }
            } catch (Exception e) {
                context.println "Failed to execute test: ${e.message}"
            }
        }
        return testStages
    }

    // Temurin remote jck trigger
    def remoteTriggerJckTests(String platform, String jdkFileName) {
        def jdkVersion = getJavaVersionNumber()
        // We just need the JDK for Jck tests
        def sdkUrl = "${env.BUILD_URL}/artifact/workspace/target/${jdkFileName}"
        context.echo "sdkUrl is ${sdkUrl}"
        def remoteTargets = [:]
        def additionalTestLabel = buildConfig.ADDITIONAL_TEST_LABEL
        def setupJCKRun = false
        if (buildConfig.SCM_REF && buildConfig.AQA_REF && sdkUrl.contains("release")) {
            setupJCKRun = true
        }
        // Determine from the platform the Jck jtx exclude platform
        def excludePlat
        def excludeRoot = "/home"
        if (platform.contains("aix")) {
            excludePlat = "aix"
        } else if (platform.contains("mac")) {
            excludePlat = "mac"
            excludeRoot = "/Users"
        } else if (platform.contains("windows")) {
            excludePlat = "windows"
            excludeRoot = "c:/Users"
        } else if (platform.contains("solaris")) {
            excludePlat = "solaris"
            excludeRoot = "/export/home"
        } else {
            excludePlat = "linux"
        }

        def appOptions="customJtx=${excludeRoot}/jenkins/jck_run/jdk${jdkVersion}/${excludePlat}/temurin.jtx"

        if (configureArguments.contains('--enable-headless-only=yes')) {
            // Headless platforms have no auto-manuals, so do not exclude any tests
            appOptions=""
        }

        def targets = ['serial': 'sanity.jck,extended.jck,special.jck']

        if ("${platform}" == 'x86-64_linux' || "${platform}" == 'x86-64_windows' || "${platform}" == 'x86-64_mac') {
            // Primary platforms run extended.jck in Parallel
            targets['serial']   = 'sanity.jck,special.jck'
            targets['parallel'] = 'extended.jck'
        }

        /*
        Here we limit the win32 testing to the burstable nodes (a subset of the available windows nodes).
        This prevents win32 tests from occupying all the Windows nodes before we can test core platform win64.
        */
        if ("${platform}" == 'x86-32_windows') {
            context.println "Windows 32bit JCK tests need the extra label hw.cpu.burstable"
            if (additionalTestLabel == '') {
                additionalTestLabel = 'hw.cpu.burstable'
            } else {
                additionalTestLabel += '&&hw.cpu.burstable'
            }
        }

        targets.each { targetMode, targetTests -> 
            try {
                context.println "Remote trigger: ${targetTests}"
                remoteTargets["${targetTests}"] = {
                    def displayName = "jdk${jdkVersion} : ${buildConfig.SCM_REF} : ${platform} : ${targetTests}"
                    def parallel = 'None'
                    def num_machines = '1'
                    if ("${targetMode}" == 'parallel') {
                         parallel = 'Dynamic'
                         num_machines = '2'
                    }
                    context.catchError {
                        context.triggerRemoteJob abortTriggeredJob: true,
                            blockBuildUntilComplete: false,
                            job: 'AQA_Test_Pipeline',
                            parameters: context.MapParameters(parameters: [context.MapParameter(name: 'SDK_RESOURCE', value: 'customized'),
                                                                    context.MapParameter(name: 'TARGETS', value: "${targetTests}"),
                                                                    context.MapParameter(name: 'CUSTOMIZED_SDK_URL', value: "${sdkUrl}"),
                                                                    context.MapParameter(name: 'JDK_VERSIONS', value: "${jdkVersion}"),
                                                                    context.MapParameter(name: 'PARALLEL', value: parallel),
                                                                    context.MapParameter(name: 'NUM_MACHINES', value: "${num_machines}"),
                                                                    context.MapParameter(name: 'PLATFORMS', value: "${platform}"),
                                                                    context.MapParameter(name: 'PIPELINE_DISPLAY_NAME', value: "${displayName}"),
                                                                    context.MapParameter(name: 'APPLICATION_OPTIONS', value: "${appOptions}"),
                                                                    context.MapParameter(name: 'LABEL_ADDITION', value: additionalTestLabel),
                                                                    context.MapParameter(name: 'cause', value: "Remote triggered by job ${env.BUILD_URL}"), // Label is lowercase on purpose to map to the Jenkins target reporting system
                                                                    context.MapParameter(name: 'SETUP_JCK_RUN', value: "${setupJCKRun}")]),
                            remoteJenkinsName: 'temurin-compliance',
                            shouldNotFailBuild: true,
                            token: 'RemoteTrigger',
                            useCrumbCache: true,
                            useJobInfoCache: true
                    }
                }
            } catch (Exception e) {
                context.println "Failed to remote trigger jck tests: ${e.message}"
            }
        }

        return remoteTargets
    }

    def compareReproducibleBuild(String nonDockerNodeName) {
        // Currently only enable for jdk17, linux_x64, temurin, nightly, which shouldn't affect current build
        // Move out of normal jdk** folder as it won't be regenerated automatically right now
        def buildJobName = "${env.JOB_NAME}"
        buildJobName = buildJobName.substring(buildJobName.lastIndexOf('/')+1)
        def comparedJobName = "${buildJobName}_reproduce_compare"
        if (!Boolean.valueOf(buildConfig.RELEASE)) {
            // For now set the build as independent, no need to wait for result as the build takes time
            String helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
            def JobHelper = context.library(identifier: "openjdk-jenkins-helper@${helperRef}").JobHelper
            if (!JobHelper.jobIsRunnable(comparedJobName as String)) {
                context.node('worker') {
                    context.println "Reproduce_compare job doesn't exist, create reproduce_compare job: ${comparedJobName}"
                    context.jobDsl scriptText: """
                        pipelineJob("${comparedJobName}") {
                            description(\'<h1>THIS IS AN AUTOMATICALLY GENERATED JOB. PLEASE DO NOT MODIFY, IT WILL BE OVERWRITTEN.</h1>\')

                            definition {
                                parameters {
                                    stringParam("COMPARED_JOB_NUMBER", "", "Compared nightly build job number.")
                                    stringParam("COMPARED_JOB_NAME", "", "Compared nightly build job name")
                                    stringParam("COMPARED_AGENT", "", "Compared nightly build job agent.")
                                    stringParam("COMPARED_JOB_PARAMS", "", "Compared nightly build job parameters")
                                }
                                cpsScm {
                                    scm {
                                        git {
                                            remote {
                                                url("https://github.com/adoptium/ci-jenkins-pipelines.git")
                                            }
                                            branch("*/master")
                                        }
                                        scriptPath("tools/reproduce_comparison/Jenkinsfile")
                                        lightweight(true)
                                    }
                                }
                                logRotator {
                                    numToKeep(30)
                                    artifactNumToKeep(10)
                                    daysToKeep(60)
                                    artifactDaysToKeep(10)
                                }
                            }
                        }
                    """ , ignoreExisting: false
                }
            }
            context.stage('Reproduce Compare') {
                def buildParams = context.params.toString()
                // passing buildParams multiline parameter to downstream job, double check the available method
                context.build job: comparedJobName,
                                propagate: false,
                                parameters: [
                                    context.string(name: 'COMPARED_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                                    context.string(name: 'COMPARED_JOB_NAME', value: "${env.JOB_NAME}"),
                                    context.string(name: 'COMPARED_AGENT', value: nonDockerNodeName),
                                    context.string(name: 'COMPARED_JOB_PARAMS', value: buildParams)
                                ],
                                wait: false
            }
        }
    }

    /*
    We use this function at the end of a build to parse a java version string and create a VersionInfo object for deployment in the metadata objects.
    E.g. 11.0.9+10-202010192351 would be one example of a matched string.
    The regex would match both OpenJDK Runtime Environment and Java(TM) SE Runtime Environment.
    */
    VersionInfo parseVersionOutput(String consoleOut) {
        context.println("Attempt to parse version output...")
        context.println(consoleOut)

        def pattern = "Runtime Environment"

        if (buildConfig.VARIANT == "openj9") {
            pattern = "IBM Semeru Runtime " + ((buildConfig.ADDITIONAL_FILE_NAME_TAG == "IBM") ? "Certified" : "Open") + " Edition"
        }

        Matcher matcher = (consoleOut =~ /(?ms)^.*${pattern}[^\n]*\(build (?<version>[^)]*)\).*$/)

        if (matcher.matches()) {
            context.println('matched')
            String versionOutput = matcher.group('version')

            context.println(versionOutput)

            return new VersionInfo(context).parse(versionOutput, buildConfig.ADOPT_BUILD_NUMBER)
        } else {
            context.println("Failed to parse version output")
        }
        return null
    }

    /*
    Run the Sign downstream job. We run this job on windows and jdk8 hotspot & jdk13 mac builds.
    The job code signs and notarizes the binaries so they can run on these operating systems without encountering issues.
    */
    def sign(VersionInfo versionInfo) {
        // Sign and archive jobs if needed

        def targets = ['windows']
        if (buildConfig.VARIANT.equals('openj9')) {
            targets.addAll(['aix', 'linux', 'mac'])
        }

        if (targets.contains(buildConfig.TARGET_OS)) {
            context.stage('sign') {
                def filter = ''

                def nodeFilter = 'eclipse-codesign'

                def signTool = 'eclipse'

                if (buildConfig.VARIANT == 'openj9') {
                    filter = "**/ibm-semeru*-j*_${buildConfig.TARGET_OS}_*.tar.gz"
                    nodeFilter = 'sw.tool.signing'
                    signTool = 'garasign'

                    if (buildConfig.TARGET_OS == 'windows') {
                        nodeFilter += '&&sw.os.windows'
                        filter = "**/ibm-semeru*-j*_${buildConfig.TARGET_OS}_*.zip"
                    } else if (['aix', 'linux', 'mac'].contains(buildConfig.TARGET_OS)) {
                        nodeFilter += '&&sw.os.linux'
                    }
                } else {
                    if (buildConfig.TARGET_OS == 'windows') {
                        filter = "**/OpenJDK*_windows_*.zip"

                    } else if (buildConfig.TARGET_OS == 'mac') {
                        filter = "**/OpenJDK*_mac_*.tar.gz"
                    }
                }

                def params = [
                        context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                        context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                        context.string(name: 'OPERATING_SYSTEM', value: "${buildConfig.TARGET_OS}"),
                        context.string(name: 'VERSION', value: "${versionInfo.major}"),
                        context.string(name: 'SIGN_TOOL', value: "${signTool}"),
                        context.string(name: 'FILTER', value: "${filter}"),
                        context.string(name: 'CERTIFICATE', value: "PRD0000192key"),
                        ['$class': 'LabelParameterValue', name: 'NODE_LABEL', label: "${nodeFilter}"],
                ]

                // Execute sign job
                def signJob = context.build job: 'build-scripts/release/sign_build',
                    propagate: true,
                    parameters: params

                context.node('worker') {
                    //Copy signed artifact back and archive again
                    context.sh 'rm workspace/target/* || true'

                    context.copyArtifacts(
                            projectName: 'build-scripts/release/sign_build',
                            selector: context.specific("${signJob.getNumber()}"),
                            filter: 'workspace/target/*',
                            fingerprintArtifacts: true,
                            target: 'workspace/target/',
                            flatten: true)

                    def extension = (buildConfig.TARGET_OS == "windows") ? "zip" : "tar.gz"
                    context.sh "cd workspace/target/ && for file in \$(ls *.${extension}); do sha256sum \"\$file\" > \$file.sha256.txt ; done"

                    writeMetadata(versionInfo, false)
                    context.archiveArtifacts artifacts: 'workspace/target/*'
                }
            }
        } else {
            context.echo "Skip signing for unsupported OS: ${buildConfig.TARGET_OS}"
        }
    }

    /*
    Run the Mac installer downstream job.
    */
    private void buildMacInstaller(VersionInfo versionData) {
        def filter = '**/OpenJDK*_mac_*.tar.gz'

        if (buildConfig.VARIANT == "openj9") {
            filter = "**/ibm-semeru*-j*_mac_*.tar.gz"
        }

        // Execute installer job
        def installerJob = context.build job: 'build-scripts/release/create_installer_mac',
                propagate: true,
                parameters: [
                        context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                        context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                        context.string(name: 'FILTER', value: "${filter}"),
                        context.string(name: 'FULL_VERSION', value: "${versionData.version}"),
                        context.string(name: 'MAJOR_VERSION', value: "${versionData.major}")
                ]

        context.copyArtifacts(
                projectName: 'build-scripts/release/create_installer_mac',
                selector: context.specific("${installerJob.getNumber()}"),
                filter: 'workspace/target/*',
                fingerprintArtifacts: true,
                target: 'workspace/target/',
                flatten: true)
    }

    /*
    Run the Linux installer downstream job.
    */
    private void buildLinuxInstaller(VersionInfo versionData) {
        def filter = "**/OpenJDK*_linux_*.tar.gz"
        def nodeFilter = "${buildConfig.TARGET_OS}&&fpm"

        if (buildConfig.VARIANT == "openj9") {
            filter = "**/*-j*_${buildConfig.ARCHITECTURE}_${buildConfig.TARGET_OS}_*.tar.gz"
            nodeFilter = "sw.os.${buildConfig.TARGET_OS}&&ci.role.packaging&&sw.tool.rpm"

            def enableSigner = Boolean.valueOf(buildConfig.ENABLE_SIGNER)
            if (buildConfig.TARGET_OS == "aix") {
                // sign tool not available on AIX nodes
                enableSigner = false
            } else {
                nodeFilter += "&&sw.tool.signing"
            }

            def version = ''
            def specVersion = ''
            def variantVersion = ''
            def variantTags = ''
            String additionalFileNameTag = buildConfig.ADDITIONAL_FILE_NAME_TAG

            if (buildConfig.RELEASE && ((additionalFileNameTag == 'IBM') && buildConfig.CONFIGURE_ARGS.contains('--with-vendor-version-string'))) {
                specVersion = getVendorVersion()

            } else if (buildConfig.PUBLISH_NAME && buildConfig.PUBLISH_NAME.contains(buildConfig.VARIANT)) {
                // expected publishName:  jdk[-]<version>_<variant>-<variant_version>[-<variant_tag>]
                //e.g.
                //  JDK8:  jdk8u192-b12_openj9-0.12.1
                //  JDK11: jdk-11.0.2+9_openj9-0.12.1-m1
                //  JDK17: jdk-17+35_openj9-0.28.0-m1

                def tokens = buildConfig.PUBLISH_NAME.minus('jdk-').minus('jdk').tokenize("_")
                version = tokens[0]

                def variantTokens = tokens[1].tokenize('-')
                variantVersion = variantTokens[1]

                if (variantTokens.size() > 2) {
                    variantTags = variantTokens[2]
                }
            }

            // launch job to build the RPM package distribution
            def installerJobName = 'build-scripts/release/package_binaries'

            try {
                def installerJob = context.build job: installerJobName,
                    propagate: true,
                    parameters: [
                            context.string(name: 'NODE_LABEL', value: "${nodeFilter}"),
                            ['$class': 'BooleanParameterValue', name: 'RELEASE', value: buildConfig.RELEASE],
                            ['$class': 'BooleanParameterValue', name: 'ENABLE_SIGNER', value: enableSigner],
                            context.string(name: 'JDK_VERSION', value: "${versionData.major}"),
                            context.string(name: 'PACKAGE_TYPE', value: 'rpm'),
                            context.string(name: 'PRODUCT', value: (buildConfig.ADDITIONAL_FILE_NAME_TAG ?: '')),
                            context.string(name: 'VARIANT', value: buildConfig.VARIANT),
                            context.string(name: 'VARIANT_VERSION', value: variantVersion),
                            context.string(name: 'VARIANT_TAG', value: variantTags),
                            context.string(name: 'VERSION', value: version),
                            context.string(name: 'SPEC_VERSION', value: specVersion),
                            context.string(name: 'OS', value: buildConfig.TARGET_OS),
                            context.string(name: 'ARCHITECTURE', value: buildConfig.ARCHITECTURE),
                            context.string(name: 'BINARIES_SOURCE', value: 'upstreamJob'),
                            context.string(name: 'UPSTREAM_JOB_NAME', value: env.JOB_NAME),
                            context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${currentBuild.getNumber()}"),
                            context.string(name: 'SCM_REPO', value: (DEFAULTS_JSON['repository']['installer_url'] ?: '')),
                            context.string(name: 'SCM_BRANCH', value: (DEFAULTS_JSON['repository']['installer_branch'] ?: ''))
                    ]

                context.copyArtifacts(
                        projectName: installerJobName,
                        selector: context.specific("${installerJob.getNumber()}"),
                        filter: '**/*',
                        target: "workspace/target/",
                        flatten: true,
                        fingerprintArtifacts: true
                )

                writeMetadata(versionData, false)
                context.archiveArtifacts artifacts: "workspace/target/*.rpm, workspace/target/*.rpm.sha256.txt, workspace/target/*.rpm.json"

            }  catch (e) {
                context.println("Failed to build ${buildConfig.TARGET_OS} installer ${e}")
                currentBuild.result = 'FAILURE'
            }

        } else {
            String releaseType = "Nightly"
            if (buildConfig.RELEASE) {
                releaseType = "Release"
            }

            // Execute installer job
            context.build job: "build-scripts/release/create_installer_linux",
                propagate: true,
                parameters: [
                        context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                        context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                        context.string(name: 'FILTER', value: "${filter}"),
                        context.string(name: 'RELEASE_TYPE', value: "${releaseType}"),
                        context.string(name: 'VERSION', value: "${versionData.version}"),
                        context.string(name: 'MAJOR_VERSION', value: "${versionData.major}"),
                        context.string(name: 'ARCHITECTURE', value: "${buildConfig.ARCHITECTURE}"),
                        ['$class': 'LabelParameterValue', name: 'NODE_LABEL', label: "${nodeFilter}"]
                ]
        }
    }

    /*
    Run the Windows installer downstream jobs.
    We run two jobs if we have a JRE (see https://github.com/adoptium/temurin-build/issues/1751).
    */
    private void buildWindowsInstaller(VersionInfo versionData, String filter, String category) {
        def nodeFilter = "sw.os.windows&&ci.role.packaging&&sw.tool.signing"

        def buildNumber = versionData.build

        if (versionData.major == 8) {
            buildNumber = String.format('%02d', versionData.build)
        }

        def INSTALLER_ARCH = "${buildConfig.ARCHITECTURE}"
        // Wix toolset requires aarch64 builds to be called arm64
        if (buildConfig.ARCHITECTURE == 'aarch64') {
            INSTALLER_ARCH = 'arm64'
        }

        // Get version patch number if one is present
        def patch_version = versionData.patch ?: 0

        // Execute installer job
        def installerJob = context.build job: 'build-scripts/release/create_installer_windows',
                propagate: true,
                parameters: [
                        context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                        context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                        context.string(name: 'FILTER', value: "${filter}"),
                        context.string(name: 'PRODUCT_MAJOR_VERSION', value: "${versionData.major}"),
                        context.string(name: 'PRODUCT_MINOR_VERSION', value: "${versionData.minor}"),
                        context.string(name: 'PRODUCT_MAINTENANCE_VERSION', value: "${versionData.security}"),
                        context.string(name: 'PRODUCT_PATCH_VERSION', value: "${patch_version}"),
                        context.string(name: 'PRODUCT_BUILD_NUMBER', value: "${buildNumber}"),
                        context.string(name: 'MSI_PRODUCT_VERSION', value: "${versionData.msi_product_version}"),
                        context.string(name: 'PRODUCT_CATEGORY', value: "${category}"),
                        context.string(name: 'JVM', value: "${buildConfig.VARIANT}"),
                        context.string(name: 'ARCH', value: "${INSTALLER_ARCH}"),
                        ['$class': 'LabelParameterValue', name: 'NODE_LABEL', label: "${nodeFilter}"]
                ]
        context.copyArtifacts(
                projectName: 'build-scripts/release/create_installer_windows',
                selector: context.specific("${installerJob.getNumber()}"),
                filter: 'wix/ReleaseDir/*',
                fingerprintArtifacts: true,
                target: 'workspace/target/',
                flatten: true)
    }

    /*
    Build installer master function. This builds the downstream installer jobs on completion of the sign and test jobs.
    The installers create our rpm, msi and pkg files that allow for an easier installation of the jdk binaries over a compressed archive.
    For Mac, we also clean up pkgs on master node from previous runs, if needed (Ref openjdk-build#2350).
    */
    def buildInstaller(VersionInfo versionData) {
        if (versionData == null || versionData.major == null) {
            context.println 'Failed to parse version number, possibly a nightly? Skipping installer steps'
            return
        }

        context.node('worker') {
            context.stage('installer') {
                switch (buildConfig.TARGET_OS) {
                    case 'aix':
                        buildLinuxInstaller(versionData)
                        break
                    case 'mac':
                        context.sh 'rm -rf workspace/target/* || true'
                        buildMacInstaller(versionData)
                        break
                    case 'linux':
                        buildLinuxInstaller(versionData)
                        break
                    case 'windows':
                        context.sh 'rm -rf workspace/target/* || true'
                        buildWindowsInstaller(versionData,'**/ibm-semeru*-jdk_*_windows*.zip', 'jdk');
                        // Copy jre artifact from current pipeline job
                        context.copyArtifacts(
                            projectName: "${env.JOB_NAME}",
                            selector: context.specific("${env.BUILD_NUMBER}"),
                            filter: '**/ibm-semeru*-jre_*_windows*.zip',
                            fingerprintArtifacts: true,
                            target: 'workspace/target/',
                            flatten: true)
                        // Check if JRE exists, if so, build another installer for it
                        if (listArchives().any { it =~ /-jre/ } ) { buildWindowsInstaller(versionData, '**/ibm-semeru*-jre_*_windows*.zip', 'jre');}
                        break
                    default:
                        break
                }

                // Archive the Mac and Windows pkg/msi
                if (buildConfig.TARGET_OS == 'mac' || buildConfig.TARGET_OS == 'windows') {
                    try {
                        context.sh 'cd workspace/target/ && for file in $(ls *.tar.gz *.pkg *.msi); do sha256sum "$file" > "$file".sha256.txt ; done'
                        writeMetadata(versionData, false)
                        context.archiveArtifacts artifacts: 'workspace/target/*'
                    } catch (e) {
                        context.println("Failed to build ${buildConfig.TARGET_OS} installer ${e}")
                        currentBuild.result = 'FAILURE'
                    }
                }

                // TODO: Archive Artifactory
            }
        }
    }

    def signInstaller(VersionInfo versionData) {
        if (versionData == null || versionData.major == null) {
            context.println 'Failed to parse version number, possibly a nightly? Skipping installer steps'
            return
        }

        context.node('worker') {
            context.stage('sign installer') {
                try {
                    // Ensure master context workspace is clean of any previous archives
                    context.sh 'rm -f workspace/target/* || true'
                    if (buildConfig.TARGET_OS == 'mac' || buildConfig.TARGET_OS == 'windows') {
                        signInstallerJob(versionData);
                        def extension = (buildConfig.TARGET_OS == "windows") ? "zip" : "tar.gz"
                        context.sh "cd target/${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE}/${buildConfig.VARIANT}/ && for file in \$(ls *.${extension}); do sha256sum \"\$file\" > \$file.sha256.txt ; done"
                        writeMetadata(versionData, false)
                        context.archiveArtifacts artifacts: 'workspace/target/*'

                    } else if (buildConfig.TARGET_OS == 'aix') {
                        signInstallerJob(versionData);
                        writeMetadata(versionData, false)
                        context.archiveArtifacts artifacts: 'workspace/target/*.rpm, workspace/target/*.rpm.sha256.txt, workspace/target/*.rpm.json'
                    }
                } catch (e) {
                    context.println("Failed to build ${buildConfig.TARGET_OS} installer ${e}")
                    currentBuild.result = 'FAILURE'
                }
            }
        }
    }

    private void signInstallerJob(VersionInfo versionData) {
        def filter = ''

        switch (buildConfig.TARGET_OS) {
            case 'aix': filter = '**/*.rpm'; break
            case 'mac': filter = '**/*_mac_*.pkg'; break
            case 'windows': filter = '**/*_windows_*.msi'; break
            default: break
        }

        // Execute sign installer job

        if (buildConfig.TARGET_OS == 'aix') {
            def nodeFilter = 'ci.role.packaging&&sw.tool.rpm&&sw.tool.signing'

            def signRpmJob = context.build job: 'build-scripts/release/sign_rpm',
                propagate: true,
                parameters: [
                        ['$class': 'BooleanParameterValue', name: 'ENABLE_SIGNER', value:  true],
                        ['$class': 'LabelParameterValue', name: 'NODE_LABEL', label: "${nodeFilter}"],
                        context.string(name: 'JDK_VERSION', value: "${versionData.major}"),
                        context.string(name: 'OS', value: buildConfig.TARGET_OS),
                        context.string(name: 'ARCHITECTURE', value: buildConfig.ARCHITECTURE),
                        context.string(name: 'PRODUCT', value: (buildConfig.ADDITIONAL_FILE_NAME_TAG ?: '')),
                        context.string(name: 'FILTER', value: "${filter}"),
                        context.string(name: 'BINARIES_SOURCE', value: 'upstreamJob'),
                        context.string(name: 'UPSTREAM_JOB_NAME', value: env.JOB_NAME),
                        context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${currentBuild.getNumber()}"),
                        context.string(name: 'SCM_REPO', value: (DEFAULTS_JSON['repository']['installer_url'] ?: '')),
                        context.string(name: 'SCM_BRANCH', value: (DEFAULTS_JSON['repository']['installer_branch'] ?: ''))
                ]

            // clean up unsigned files
            context.sh 'cd workspace/target/ && rm -f *.rpm *.rpm.sha256.txt'

            context.copyArtifacts(
                    projectName: 'build-scripts/release/sign_rpm',
                    selector: context.specific("${signRpmJob.getNumber()}"),
                    filter: '**/*',
                    target: 'workspace/target/',
                    flatten: true,
                    fingerprintArtifacts: true
            )

        } else {
            def installerJob = context.build job: 'build-scripts/release/sign_installer',
                    propagate: true,
                    parameters: [
                            context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                            context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                            context.string(name: 'FILTER', value: "${filter}"),
                            context.string(name: 'FULL_VERSION', value: "${versionData.version}"),
                            context.string(name: 'OPERATING_SYSTEM', value: "${buildConfig.TARGET_OS}"),
                            context.string(name: 'MAJOR_VERSION', value: "${versionData.major}")
                    ]
            context.copyArtifacts(
                    projectName: 'build-scripts/release/sign_installer',
                    selector: context.specific("${installerJob.getNumber()}"),
                    filter: 'workspace/target/*',
                    fingerprintArtifacts: true,
                    target: 'workspace/target/',
                    flatten: true)
        }
    }

    // For Windows and Mac verify that all necessary executables are Signed and Notarized(mac)
    private void verifySigning() {
        if (buildConfig.TARGET_OS == "windows" || buildConfig.TARGET_OS == "mac") {
            try {
                context.println "RUNNING sign_verification for ${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE} ..."

                // Determine suitable node to run on
                def verifyNode
                if (buildConfig.TARGET_OS == "windows") {
                    verifyNode = "ci.role.test&&sw.os.windows"
                } else {
                    verifyNode = "ci.role.test&&(sw.os.osx||sw.os.mac)&&!sw.os.osx.10_14"
                }
                if (buildConfig.ARCHITECTURE == "aarch64") {
                    verifyNode = verifyNode + "&&hw.arch.aarch64"
                } else {
                    verifyNode = verifyNode + "&&hw.arch.x86"
                }

                // Execute sign verification job
                context.build job: 'build-scripts/release/sign_verification',
                    propagate: true,
                    parameters: [
                            context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                            context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                            context.string(name: 'TARGET_OS', value: "${buildConfig.TARGET_OS}"),
                            context.string(name: 'TARGET_ARCH', value: "${buildConfig.ARCHITECTURE}"),
                            context.string(name: 'NODE_LABEL', value: "${verifyNode}")
                    ]
            } catch (e) { 
                context.println("Failed to sign_verification for ${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE} ${e}")
                currentBuild.result = 'FAILURE'
            } 
        }
    }

    private void gpgSign() {
        context.stage('GPG sign') {
            context.println "RUNNING sign_temurin_gpg for ${buildConfig.TARGET_OS}/${buildConfig.ARCHITECTURE} ..."

            def params = [
                  context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                  context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                  context.string(name: 'UPSTREAM_DIR', value: 'workspace/target')
           ]

            def signSHAsJob = context.build job: 'build-scripts/release/sign_temurin_gpg',
               propagate: true,
               parameters: params

            context.node('worker') {
                // Remove any previous workspace artifacts
                context.sh 'rm -rf workspace/target/* || true'
                context.copyArtifacts(
                    projectName: 'build-scripts/release/sign_temurin_gpg',
                    selector: context.specific("${signSHAsJob.getNumber()}"),
                    filter: '**/*.sig',
                    fingerprintArtifacts: true,
                    target: 'workspace/target/',
                    flatten: true)

                // Archive GPG signatures in Jenkins
                try {
                    context.timeout(time: buildTimeouts.ARCHIVE_ARTIFACTS_TIMEOUT, unit: 'HOURS') {
                        context.archiveArtifacts artifacts: 'workspace/target/*.sig'
                    }
               } catch (FlowInterruptedException e) {
                    throw new Exception("[ERROR] Archive artifact timeout (${buildTimeouts.ARCHIVE_ARTIFACTS_TIMEOUT} HOURS) for ${downstreamJobName} has been reached. Exiting...")
                }
            }
        }
    }
    /*
    Lists and returns any compressed archived or sbom file contents of the top directory of the build node
    */
    List<String> listArchives() {
        def files = context.sh(
                script: '''find workspace/target/ | egrep -e '(\\.tar\\.gz|\\.zip|\\.msi|\\.pkg|\\.deb|\\.rpm|-sbom_.*\\.json)$' ''',
                returnStdout: true,
                returnStatus: false
        )
                .trim()
                .split('\n')
                .toList()

        context.println "listArchives: ${files}"

        return files
    }

    /*
    On any writeMetadata other than the first, we simply return a MetaData object from the previous writeout adjusted to the situation.
    On the first writeout, we pull in the .txt files created by the build that list the attributes of what we used to build the jdk (e.g. configure args, commit hash, etc)
    */
    MetaData formMetadata(VersionInfo version, Boolean initialWrite) {
        // We have to setup some attributes for the first run since formMetadata is sometimes initiated from downstream job on master node with no access to the required files
        if (initialWrite) {
            // Get scmRef
            context.println 'INFO: FIRST METADATA WRITE OUT! Checking if we have a scm reference in the build config...'

            String scmRefPath = 'workspace/target/metadata/scmref.txt'
            scmRef = buildConfig.SCM_REF

            if (scmRef != '') {
                // Use the buildConfig scmref if it is set
                context.println "SUCCESS: SCM_REF has been set (${buildConfig.SCM_REF})! Using it to build the initial metadata over ${scmRefPath}..."
            } else {
                // If we don't have a scmref set in config, check if we have a scmref from the build
                context.println "INFO: SCM_REF is NOT set. Attempting to read ${scmRefPath}..."
                try {
                    scmRef = context.readFile(scmRefPath).trim()
                    context.println "SUCCESS: scmref.txt found: ${scmRef}"
                } catch (NoSuchFileException e) {
                    // In rare cases, we will fail to create the scmref.txt file
                    context.println "WARNING: $scmRefPath was not found. Using build config SCM_REF instead (even if it's empty)..."
                }
            }

            // Get Full Version Output
            String versionPath = 'workspace/target/metadata/version.txt'
            if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
                versionPath = crossCompileVersionPath
            }
            context.println "INFO: Attempting to read ${versionPath}..."

            try {
                fullVersionOutput = context.readFile(versionPath)
                context.println "SUCCESS: ${versionPath} found"
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${versionPath} was not found. Exiting...")
            }

            // Get Configure Args
            String configurePath = 'workspace/target/metadata/configure.txt'
            context.println "INFO: Attempting to read ${configurePath}..."

            try {
                configureArguments = context.readFile(configurePath)
                context.println 'SUCCESS: configure.txt found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${configurePath} was not found. Exiting...")
            }

            // Get make command args
            String makeCommandArgPath = 'workspace/target/metadata/makeCommandArg.txt'
            context.println "INFO: Attempting to read ${makeCommandArgPath}..."

            try {
                makeCommandArgs = context.readFile(makeCommandArgPath)
                context.println 'SUCCESS: makeCommandArg.txt found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${makeCommandArgPath} was not found. Exiting...")
            }

            // Get Variant Version for OpenJ9
            if (buildConfig.VARIANT == 'openj9') {
                String j9MajorPath = 'workspace/target/metadata/variant_version/major.txt'
                String j9MinorPath = 'workspace/target/metadata/variant_version/minor.txt'
                String j9SecurityPath = 'workspace/target/metadata/variant_version/security.txt'
                String j9TagsPath = 'workspace/target/metadata/variant_version/tags.txt'

                context.println 'INFO: Build variant openj9 detected...'

                context.println 'INFO: Attempting to read workspace/target/metadata/variant_version/major.txt...'
                try {
                    j9Major = context.readFile(j9MajorPath)
                    context.println "SUCCESS: major.txt found with value '${j9Major}'"
                } catch (NoSuchFileException e) {
                    throw new Exception("ERROR: ${j9MajorPath} was not found. Exiting...")
                }

                context.println 'INFO: Attempting to read workspace/target/metadata/variant_version/minor.txt...'
                try {
                    j9Minor = context.readFile(j9MinorPath)
                    context.println "SUCCESS: minor.txt found  with value '${j9Minor}'"
                } catch (NoSuchFileException e) {
                    throw new Exception("ERROR: ${j9MinorPath} was not found. Exiting...")
                }

                context.println 'INFO: Attempting to read workspace/target/metadata/variant_version/security.txt...'
                try {
                    j9Security = context.readFile(j9SecurityPath)
                    context.println "SUCCESS: security.txt found with value '${j9Security}'"
                } catch (NoSuchFileException e) {
                    throw new Exception("ERROR: ${j9SecurityPath} was not found. Exiting...")
                }

                context.println 'INFO: Attempting to read workspace/target/metadata/variant_version/tags.txt...'
                try {
                    j9Tags = context.readFile(j9TagsPath)
                    context.println "SUCCESS: tags.txt found with value '${j9Tags}'"
                } catch (NoSuchFileException e) {
                    throw new Exception("ERROR: ${j9TagsPath} was not found. Exiting...")
                }

                variantVersion = [major: j9Major, minor: j9Minor, security: j9Security, tags: j9Tags]
            }

            // Get Vendor
            String vendorPath = 'workspace/target/metadata/vendor.txt'
            context.println "INFO: Attempting to read ${vendorPath}..."

            try {
                vendorName = context.readFile(vendorPath)
                context.println 'SUCCESS: vendor.txt found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${vendorPath} was not found. Exiting...")
            }

            // Get Build Source
            String buildSourcePath = 'workspace/target/metadata/buildSource.txt'
            context.println "INFO: Attempting to read ${buildSourcePath}..."

            try {
                buildSource = context.readFile(buildSourcePath)
                context.println 'SUCCESS: buildSource.txt found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${buildSourcePath} was not found. Exiting...")
            }

            // Get OpenJDK Source
            String openjdkSourcePath = 'workspace/target/metadata/openjdkSource.txt'
            context.println "INFO: Attempting to read ${openjdkSourcePath}..."

            try {
                openjdkSource = context.readFile(openjdkSourcePath)
                context.println 'SUCCESS: openjdkSource.txt found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${openjdkSourcePath} was not found. Exiting...")
            }

            // Get built OPENJDK BUILD_CONFIG
            String openjdkBuiltConfigPath = 'workspace/config/built_config.cfg'
            context.println "INFO: Attempting to read ${openjdkBuiltConfigPath}..."
            try {
                openjdk_built_config = context.readFile(openjdkBuiltConfigPath)
                context.println 'SUCCESS: built_config.cfg found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${openjdkBuiltConfigPath} was not found. Exiting...")
            }

            // Get built makejdk-any-platforms.sh args
            String makejdkArgsPath = 'workspace/config/makejdk-any-platform.args'
            context.println "INFO: Attempting to read ${makejdkArgsPath}..."
            try {
                makejdkArgs = context.readFile(makejdkArgsPath)
                context.println 'SUCCESS: makejdk-any-platform.args found'
            } catch (NoSuchFileException e) {
                throw new Exception("ERROR: ${makejdkArgsPath} was not found. Exiting...")
            }

            // Get dependency_versions
            def deps = ['alsa', 'freetype', 'freemarker']
            for (dep in deps) {
                String depVerPath = "workspace/target/metadata/dependency_version_${dep}.txt"
                context.println "INFO: Attempting to read ${depVerPath}..."
                if (context.fileExists(depVerPath)) {
                    def depVer = context.readFile(depVerPath)
                    context.println "SUCCESS: dependency_version_${dep}.txt found: ${depVer}"
                    dependency_version["${dep}"] = depVer
                } else {
                    context.println "${depVerPath} was not found, no metadata set."
                    dependency_version["${dep}"] = ''
                }
            }
        }

        return new MetaData(
            vendorName,
            buildConfig.TARGET_OS,
            scmRef,
            buildSource,
            version,
            buildConfig.JAVA_TO_BUILD,
            buildConfig.VARIANT,
            variantVersion,
            buildConfig.ARCHITECTURE,
            fullVersionOutput,
            makejdkArgs,
            configureArguments,
            makeCommandArgs,
            buildConfig.toJson(),
            openjdk_built_config,
            openjdkSource,
            dockerImageDigest,
            dependency_version['alsa'],
            dependency_version['freetype'],
            dependency_version['freemarker']
        )
    }

    /*
    Calculates and writes out the metadata to a file.
    The metadata defines and summarises a build and the jdk it creates.
    https://api.adoptium.net/ v3 api makes use of it in its endpoints to quickly display information about the jdk binaries that are stored on github.
    */
    def writeMetadata(VersionInfo version, Boolean initialWrite) {
        /*
        example data:
            {
                "vendor": "Eclipse Adoptium",
                "os": "mac",
                "arch": "x64",
                "variant": "openj9",
                "variant_version": {
                    "major": "0",
                    "minor": "22",
                    "security": "0",
                    "tags": "m2"
                },
                "version": {
                    "minor": 0,
                    "security": 0,
                    "pre": null,
                    "adopt_build_number": 0,
                    "major": 15,
                    "version": "15+29-202007070926",
                    "semver": "15.0.0+29.0.202007070926",
                    "build": 29,
                    "opt": "202007070926"
                },
                "scmRef": "<output of git describe OR buildConfig.SCM_REF>",
                "buildRef": "<build-repo-name/build-commit-sha>",
                "version_data": "jdk15",
                "binary_type": "debugimage",
                "sha256": "<shasum>",
                "full_version_output": <output of java --version>,
                "configure_arguments": <output of bash configure>,
                "make_command_args" : <make command args>,
                "BUILD_CONFIGURATION_param": <build job BUILD_CONFIGURATION param json>,
                "openjdk_built_config" : <built BUILD_CONFIG>
            }
        */

        MetaData data = formMetadata(version, initialWrite)
        Boolean metaWrittenOut = false
        listArchives().each({ file ->
            def type = 'jdk'
            if (file.contains('-jre')) {
                type = 'jre'
            } else if (file.contains('-testimage')) {
                type = 'testimage'
            } else if (file.contains('-debugimage')) {
                type = 'debugimage'
            } else if (file.contains('-static-libs')) {
                type = 'staticlibs'
            } else if (file.contains('-sources')) {
                type = 'sources'
            } else if (file.contains('-sbom')) {
                type = 'sbom'
            }

            String hash = context.sh(script: """\
                                              if [ -x "\$(command -v shasum)" ]; then
                                                (shasum -a 256 | cut -f1 -d' ') <$file
                                              else
                                                sha256sum $file | cut -f1 -d' '
                                              fi
                                            """.stripIndent(), returnStdout: true, returnStatus: false)

            hash = hash.replaceAll('\n', '')

            data.binary_type = type
            data.sha256 = hash

            // To save on spam, only print out the metadata the first time
            if (!metaWrittenOut && initialWrite) {
                context.println '===METADATA OUTPUT==='
                context.println JsonOutput.prettyPrint(JsonOutput.toJson(data.asMap()))
                context.println '=/=METADATA OUTPUT=/='
                metaWrittenOut = true
            }

            // Special handling for sbom metadata file (to be backwards compatible for api service)
            // from "*sbom<XXX>.json" to "*sbom<XXX>-metadata.json"
            if (file.contains('sbom')) {
                context.writeFile file: file.replace('.json', '-metadata.json'), text: JsonOutput.prettyPrint(JsonOutput.toJson(data.asMap()))
            } else {
                context.writeFile file: "${file}.json", text: JsonOutput.prettyPrint(JsonOutput.toJson(data.asMap()))
            }
        })
    }

    /*
    Calculates what the binary filename will be based off of the version, arch, os, variant, timestamp and extension.
    It will usually be something like OpenJDK8U-jdk_x64_linux_temurin_2020-10-19-17-06.tar.gz
    */
    def determineFileName() {
        String javaToBuild = buildConfig.JAVA_TO_BUILD
        String architecture = buildConfig.ARCHITECTURE
        String os = buildConfig.TARGET_OS
        String variant = buildConfig.VARIANT
        String additionalFileNameTag = buildConfig.ADDITIONAL_FILE_NAME_TAG
        String overrideFileNameVersion = buildConfig.OVERRIDE_FILE_NAME_VERSION

        def extension = 'tar.gz'

        if (os == 'windows') {
            extension = 'zip'
        }

        javaToBuild = javaToBuild.trim().toUpperCase()

        // Add "U" to javaToBuild filename prefix for non-head versions
        if (!javaToBuild.endsWith('U') && !javaToBuild.equals('JDK')) {
            javaToBuild += 'U'
        }

        def fileName = "Open${javaToBuild}-jdk_${architecture}_${os}_${variant}"
        if (variant == 'temurin') {
            // For compatibility with existing releases
            fileName = "Open${javaToBuild}-jdk_${architecture}_${os}_hotspot"
        }

        if (variant == "openj9") {
             fileName = "ibm-semeru-" + ((additionalFileNameTag == "IBM") ? "certified" : "open") + "-jdk_${architecture}_${os}"
        }

        if (overrideFileNameVersion) {
            fileName = "${fileName}_${overrideFileNameVersion}"
        } else if ((buildConfig.PUBLISH_NAME) && (additionalFileNameTag != 'IBM')) {
            // for java 11 remove jdk- and +. i.e jdk-11.0.3+7 -> 11.0.3_7_openj9-0.14.0
            def nameTag = buildConfig.PUBLISH_NAME
                    .replace('jdk-', '')
                    .replaceAll("\\+", '_')

            // for java 8 remove jdk and - before the build. i.e jdk8u212-b03_openj9-0.14.0 -> 8u212b03_openj9-0.14.0
            nameTag = nameTag
                    .replace('jdk', '')
                    .replace('-b', 'b')

            fileName = "${fileName}_${nameTag}"
        } else if ((buildConfig.RELEASE) && ((additionalFileNameTag == "IBM") && buildConfig.CONFIGURE_ARGS.contains('--with-vendor-version-string'))) {
            fileName = "${fileName}_${getVendorVersion()}"
        } else {
            def timestamp = new Date().format('yyyy-MM-dd-HH-mm', TimeZone.getTimeZone('UTC'))

            if(variant == "openj9") {
                fileName = "${fileName}_${javaToBuild}_${timestamp}"
            } else {
                fileName = "${fileName}_${timestamp}"
            }
        }

        fileName = "${fileName}.${extension}"

        context.println "Filename will be: $fileName"
        return fileName
    }

    /*
    Return the vendor version from the configure arguments when the
    --with-vendor-version-string option is set, otherwise return an empty string.
     */
    def getVendorVersion() {
        def vendorVersion = ''
        def configureArgs = buildConfig.CONFIGURE_ARGS
        def startIndex = configureArgs.indexOf('--with-vendor-version-string=\"')

        if (startIndex != 1) {
            // extract vendor version string from buildConfig.CONFIGURE_ARGS
            //  e.g. "CONFIGURE_ARGS": "--with-vendor-version-string=\"11.0.12.0\""

            startIndex += 30
            vendorVersion = configureArgs.substring(startIndex, configureArgs.indexOf('\"', startIndex))
        }

        return vendorVersion
    }

    /*
    Run the cross comile version reader downstream job.
    In short, we archive the build artifacts to expose them to the job and run ./java version, copying the output back to here.
    See cross_compiled_version_out.groovy.
    */
    def readCrossCompiledVersionString() {
        // Archive the artifacts early so we can copy them over to the downstream job
        try {
            context.timeout(time: buildTimeouts.BUILD_ARCHIVE_TIMEOUT, unit: 'HOURS') {
                context.archiveArtifacts artifacts: 'workspace/target/*'
            }
        } catch (FlowInterruptedException e) {
            throw new Exception("[ERROR] Build archive timeout (${buildTimeouts.BUILD_ARCHIVE_TIMEOUT} HOURS) has been reached. Exiting...")
        }

        // TODO Archive Artifacotry ???

        // Setup params for downstream job & execute
        String shortJobName = env.JOB_NAME.split('/').last()
        String copyFileFilter = "${shortJobName}_${env.BUILD_NUMBER}_version.txt"

        def nodeFilter = "${buildConfig.TARGET_OS}&&${buildConfig.ARCHITECTURE}"

        def filter = ''

        if (buildConfig.TARGET_OS == 'windows') {
            filter = "**\\OpenJDK*-jdk*_windows_*.zip"
        } else {
            filter = "OpenJDK*-jdk*_${buildConfig.TARGET_OS}_*.tar.gz"
        }

        def crossCompileVersionOut = context.build job: 'build-scripts/utils/cross-compiled-version-out',
            propagate: true,
            parameters: [
                context.string(name: 'UPSTREAM_JOB_NAME', value: "${env.JOB_NAME}"),
                context.string(name: 'UPSTREAM_JOB_NUMBER', value: "${env.BUILD_NUMBER}"),
                context.string(name: 'JDK_FILE_FILTER', value: "${filter}"),
                context.string(name: 'FILENAME', value: "${copyFileFilter}"),
                context.string(name: 'NODE', value: "${nodeFilter}"),
                context.string(name: 'OS', value: "${buildConfig.TARGET_OS}")
            ]

        context.copyArtifacts(
            projectName: 'build-scripts/utils/cross-compiled-version-out',
            selector: context.specific("${crossCompileVersionOut.getNumber()}"),
            filter: "CrossCompiledVersionOuts/${copyFileFilter}",
            target: 'workspace/target/metadata',
            flatten: true
        )

        // We assign to a variable so it can be used in formMetadata() to find the correct version info
        crossCompileVersionPath = "workspace/target/metadata/${copyFileFilter}"
        return context.readFile(crossCompileVersionPath)
    }

    /*
     Display the current git repo information
     */
    def printGitRepoInfo() {
        context.println 'Checked out repo:'
        context.sh(script: 'git status')
        context.println 'Checked out HEAD commit SHA:'
        context.sh(script: 'git rev-parse HEAD')
    }

    /*
    Executed on a build node, the function checks out the repository and executes the build via ./make-adopt-build-farm.sh
    Once the build completes, it will calculate its version output, commit the first metadata writeout, and archive the build results.
    Running in downstream job jdk-*-*-* build stage, called by build()
    */
    def buildScripts(
        cleanWorkspace,
        cleanWorkspaceAfter,
        cleanWorkspaceBuildOutputAfter,
        filename,
        useAdoptShellScripts
    ) {
        return context.stage('build') {
            // Create the repo handler with the user's defaults to ensure a temurin-build checkout is not null
            // Pass actual ADOPT_DEFAULTS_JSON, and optional buildConfig CI and BUILD branch/tag overrides,
            // so that RepoHandler checks out the desired repo correctly
            def repoHandler = new RepoHandler(USER_REMOTE_CONFIGS, ADOPT_DEFAULTS_JSON, buildConfig.CI_REF, buildConfig.BUILD_REF)
            repoHandler.setUserDefaultsJson(context, DEFAULTS_JSON['defaultsUrl'])

            context.println 'USER_REMOTE_CONFIGS: '
            context.println JsonOutput.toJson(USER_REMOTE_CONFIGS)
            context.println 'DEFAULTS_JSON: '
            context.println JsonOutput.toJson(DEFAULTS_JSON)
            context.println 'ADOPT_DEFAULTS_JSON: '
            context.println JsonOutput.toJson(ADOPT_DEFAULTS_JSON)
            context.println 'Optional branch/tag/commitSHA overrides:'
            context.println '    buildConfig.CI_REF: ' + buildConfig.CI_REF
            context.println '    buildConfig.BUILD_REF: ' + buildConfig.BUILD_REF
            context.println '    buildConfig.HELPER_REF: ' + buildConfig.HELPER_REF

            if (cleanWorkspace) {
                try {
                    try {
                        context.timeout(time: buildTimeouts.NODE_CLEAN_TIMEOUT, unit: 'HOURS') {
                            // Clean non-hidden files first
                            // Note: Underlying org.apache DirectoryScanner used by cleanWs has a bug scanning where it misses files containing ".." so use rm -rf instead
                            // Issue: https://issues.jenkins.io/browse/JENKINS-64779
                            if (context.WORKSPACE != null && !context.WORKSPACE.isEmpty()) {
                                context.println 'Cleaning workspace non-hidden files: ' + context.WORKSPACE + '/*'
                                context.sh(script: 'rm -rf ' + context.WORKSPACE + '/*')
                            } else {
                                context.println 'Warning: Unable to clean workspace as context.WORKSPACE is null/empty'
                            }

                            // Clean remaining hidden files using cleanWs
                            try {
                                context.println 'Cleaning workspace hidden files using cleanWs: ' + context.WORKSPACE
                                context.cleanWs notFailBuild: true, disableDeferredWipeout: true, deleteDirs: true
                            } catch (e) {
                                context.println "Failed to clean ${e}"
                            }
                        }
                    } catch (FlowInterruptedException e) {
                        throw new Exception("[ERROR] Node Clean workspace timeout (${buildTimeouts.NODE_CLEAN_TIMEOUT} HOURS) has been reached. Exiting...")
                    }
                } catch (e) {
                    context.println "[WARNING] Failed to clean workspace: ${e}"
                }
            }

            try {
                context.timeout(time: buildTimeouts.NODE_CHECKOUT_TIMEOUT, unit: 'HOURS') {
                    if (useAdoptShellScripts) {
                        repoHandler.checkoutAdoptPipelines(context)
                    } else {
                        repoHandler.setUserDefaultsJson(context, DEFAULTS_JSON)
                        repoHandler.checkoutUserPipelines(context)
                    }

                    // Perform a git clean outside of checkout to avoid the Jenkins enforced 10 minute timeout
                    // https://github.com/adoptium/infrastucture/issues/1553
                    context.sh(script: 'git clean -fdx')

                    printGitRepoInfo()
                }
            } catch (FlowInterruptedException e) {
                throw new Exception("[ERROR] Node checkout workspace timeout (${buildTimeouts.NODE_CHECKOUT_TIMEOUT} HOURS) has been reached. Exiting...")
            }

            try {
                // Convert IndividualBuildConfig to jenkins env variables
                List<String> envVars = buildConfig.toEnvVars()
                envVars.add("FILENAME=${filename}" as String)

                // Use BUILD_REF override if specified
                def adoptBranch = buildConfig.BUILD_REF ?: ADOPT_DEFAULTS_JSON['repository']['build_branch']

                // Add platform config path so it can be used if the user doesn't have one
                def splitAdoptUrl = ((String)ADOPT_DEFAULTS_JSON['repository']['build_url']) - ('.git').split('/')
                // e.g. https://github.com/adoptium/temurin-build.git will produce adoptium/temurin-build
                String userOrgRepo = "${splitAdoptUrl[splitAdoptUrl.size() - 2]}/${splitAdoptUrl[splitAdoptUrl.size() - 1]}"
                // e.g. adoptium/temurin-build/master/build-farm/platform-specific-configurations
                envVars.add("ADOPT_PLATFORM_CONFIG_LOCATION=${userOrgRepo}/${adoptBranch}/${ADOPT_DEFAULTS_JSON['configDirectories']['platform']}" as String)

                // Execute build
                context.withEnv(envVars) {
                    try {
                        context.timeout(time: buildTimeouts.BUILD_JDK_TIMEOUT, unit: 'HOURS') {
                            // Set Github Commit Status
                            if (env.JOB_NAME.contains('pr-tester')) {
                                updateGithubCommitStatus('PENDING', 'Build Started')
                            }
                            if (useAdoptShellScripts) {
                                context.println '[CHECKOUT] Checking out to adoptium/temurin-build...'
                                repoHandler.checkoutAdoptBuild(context)
                                printGitRepoInfo()
                                if ((buildConfig.TARGET_OS == 'mac' || buildConfig.TARGET_OS == 'windows') && buildConfig.JAVA_TO_BUILD != 'jdk8u') {
                                    context.println "Processing exploded build, sign JMODS, and assemble build, for platform ${buildConfig.TARGET_OS} version ${buildConfig.JAVA_TO_BUILD}"
                                    def signBuildArgs
                                    if (env.BUILD_ARGS != null && !env.BUILD_ARGS.isEmpty()) {
                                        signBuildArgs = env.BUILD_ARGS + ' --make-exploded-image'
                                    } else {
                                        signBuildArgs = '--make-exploded-image'
                                    }
                                    context.withEnv(['BUILD_ARGS=' + signBuildArgs]) {
                                        context.println 'Building an exploded image for signing'
                                        context.sh(script: "./${ADOPT_DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
                                    }
                                    def base_path = context.sh(script: "ls -d workspace/build/src/build/* | tr -d '\\n'", returnStdout:true)
                                    context.println "base build path for jmod signing = ${base_path}"
                                    context.stash name: 'jmods',
                                        includes: "${base_path}/hotspot/variant-server/**/*," +
                                            "${base_path}/support/modules_cmds/**/*," +
                                            "${base_path}/support/modules_libs/**/*," +
                                            // JDK 16 + jpackage needs to be signed as well stash the resources folder containing the executables
                                            "${base_path}/jdk/modules/jdk.jpackage/jdk/jpackage/internal/resources/*"

                                    context.node('eclipse-codesign') {
                                        context.sh "rm -rf ${base_path}/* || true"

                                        repoHandler.checkoutAdoptBuild(context)
                                        printGitRepoInfo()

                                        // Copy pre assembled binary ready for JMODs to be codesigned
                                        context.unstash 'jmods'
                                        def target_os = "${buildConfig.TARGET_OS}"
                                        context.withEnv(['base_os='+target_os, 'base_path='+base_path]) {
                                            // groovylint-disable
                                            context.sh '''
                                                #!/bin/bash
                                                set -eu
                                                echo "Signing JMOD files under build path ${base_path} for base_os ${base_os}"
                                                TMP_DIR="${base_path}/"
                                                if [ "${base_os}" == "mac" ]; then
                                                    ENTITLEMENTS="$WORKSPACE/entitlements.plist"
                                                    FILES=$(find "${TMP_DIR}" -perm +111 -type f -o -name '*.dylib' -type f || find "${TMP_DIR}" -perm /111 -type f -o -name '*.dylib'  -type f)
                                                else
                                                    FILES=$(find "${TMP_DIR}" -type f -name '*.exe' -o -name '*.dll')
                                                fi
                                                for f in $FILES
                                                do
                                                    echo "Signing $f using Eclipse Foundation codesign service"
                                                    dir=$(dirname "$f")
                                                    file=$(basename "$f")
                                                    mv "$f" "${dir}/unsigned_${file}"
                                                    success=false
                                                    if [ "${base_os}" == "mac" ]; then
                                                        if ! curl --fail --silent --show-error -o "$f" -F file="@${dir}/unsigned_${file}" -F entitlements="@$ENTITLEMENTS" https://cbi.eclipse.org/macos/codesign/sign; then
                                                            echo "curl command failed, sign of $f failed"
                                                        else
                                                            success=true
                                                        fi
                                                    else
                                                        if ! curl --fail --silent --show-error -o "$f" -F file="@${dir}/unsigned_${file}" https://cbi.eclipse.org/authenticode/sign; then
                                                            echo "curl command failed, sign of $f failed"
                                                        else
                                                            success=true
                                                        fi
                                                    fi
                                                    if [ $success == false ]; then
                                                        # Retry up to 20 times
                                                        max_iterations=20
                                                        iteration=1
                                                        echo "Code Not Signed For File $f"
                                                        while [ $iteration -le $max_iterations ] && [ $success = false ]; do
                                                            echo $iteration Of $max_iterations
                                                            sleep 1
                                                            if [ "${base_os}" == "mac" ]; then
                                                                if curl --fail --silent --show-error -o "$f" -F file="@${dir}/unsigned_${file}" -F entitlements="@$ENTITLEMENTS" https://cbi.eclipse.org/macos/codesign/sign; then
                                                                    success=true
                                                                fi
                                                            else
                                                                if curl --fail --silent --show-error -o "$f" -F file="@${dir}/unsigned_${file}" https://cbi.eclipse.org/authenticode/sign; then
                                                                    success=true
                                                                fi
                                                            fi

                                                            if [ $success = false ]; then
                                                                echo "curl command failed, $f Failed Signing On Attempt $iteration"
                                                                iteration=$((iteration+1))
                                                                if [ $iteration -gt $max_iterations ]
                                                                then
                                                                    echo "Errors Encountered During Signing"
                                                                    exit 1
                                                                fi
                                                            else
                                                                echo "$f Signed OK On Attempt $iteration"
                                                            fi
                                                        done
                                                    fi
                                                    chmod --reference="${dir}/unsigned_${file}" "$f"
                                                    rm -rf "${dir}/unsigned_${file}"
                                                done
                                            '''
                                            // groovylint-enable
                                        }
                                        context.stash name: 'signed_jmods', includes: "${base_path}/**/*"
                                    }

                                    // Remove jmod directories to be replaced with the stash saved above
                                    context.sh "rm -rf ${base_path}/hotspot/variant-server || true"
                                    context.sh "rm -rf ${base_path}/support/modules_cmds || true"
                                    context.sh "rm -rf ${base_path}/support/modules_libs || true"
                                    // JDK 16 + jpackage executables need to be signed as well
                                    if (buildConfig.JAVA_TO_BUILD != 'jdk11u') {
                                        context.sh "rm -rf ${base_path}/jdk/modules/jdk.jpackage/jdk/jpackage/internal/resources/* || true"
                                    }

                                    // Restore signed JMODs
                                    context.unstash 'signed_jmods'

                                    def assembleBuildArgs
                                    if (env.BUILD_ARGS != null && !env.BUILD_ARGS.isEmpty()) {
                                        assembleBuildArgs = env.BUILD_ARGS + ' --assemble-exploded-image'
                                    } else {
                                        assembleBuildArgs = '--assemble-exploded-image'
                                    }
                                    context.withEnv(['BUILD_ARGS=' + assembleBuildArgs]) {
                                        context.println 'Assembling the exploded image'
                                        context.sh(script: "./${ADOPT_DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
                                    }
                                } else {
                                    context.sh(script: "./${ADOPT_DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
                                }
                                context.println '[CHECKOUT] Reverting pre-build adoptium/temurin-build checkout...'
                                // Special case for the pr tester as checking out to the user's pipelines doesn't play nicely
                                if (env.JOB_NAME.contains('pr-tester')) {
                                    context.checkout context.scm
                                } else {
                                    repoHandler.setUserDefaultsJson(context, DEFAULTS_JSON)
                                    repoHandler.checkoutUserPipelines(context)
                                }
                                printGitRepoInfo()
                            } else {
                                context.println "[CHECKOUT] Checking out to the user's temurin-build..."

                                repoHandler.setUserDefaultsJson(context, DEFAULTS_JSON)
                                repoHandler.checkoutUserBuild(context)
                                printGitRepoInfo()
                                if (buildConfig.VARIANT == "openj9") {
                                    def openjceplusBuildArgs = ''
                                    if (env.BUILD_ARGS != null && !env.BUILD_ARGS.isEmpty()) {
                                        openjceplusBuildArgs = env.BUILD_ARGS + ' '
                                    }
                                    def javaVersion = getJavaVersionNumber()
                                    if (!DEFAULTS_JSON['exclude-openjceplus'].contains(javaVersion)) {
                                        openjceplusBuildArgs += '--bundle-openjceplus'
                                    }

                                    context.withEnv(['BUILD_ARGS=' + openjceplusBuildArgs]) {
                                        context.sshagent(['83181e25-eea4-4f55-8b3e-e79615733226']) {
                                            context.withCredentials([context.usernamePassword(credentialsId: '7c1c2c28-650f-49e0-afd1-ca6b60479546', passwordVariable: 'GSKIT_PASSWORD', usernameVariable: 'GSKIT_USERNAME')]) {
                                                context.sh(script: "./${DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
                                            }
                                        }
                                    }
                                } else {
                                    context.sh(script: "./${DEFAULTS_JSON['scriptDirectories']['buildfarm']}")
                                }

                                context.println '[CHECKOUT] Reverting pre-build user temurin-build checkout...'
                                repoHandler.checkoutUserPipelines(context)
                                printGitRepoInfo()
                            }
                        }
                    } catch (FlowInterruptedException e) {
                        // Set Github Commit Status
                        if (env.JOB_NAME.contains('pr-tester')) {
                            updateGithubCommitStatus('FAILED', 'Build FAILED')
                        }
                        throw new Exception("[ERROR] Build JDK timeout (${buildTimeouts.BUILD_JDK_TIMEOUT} HOURS) has been reached. Exiting...")
                    }

                    // Run a downstream job on riscv machine that returns the java version. Otherwise, just read the version.txt
                    String versionOut
                    if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
                        context.println "[WARNING] Don't read faked version.txt on cross compiled build! Archiving early and running downstream job to retrieve java version..."
                        versionOut = readCrossCompiledVersionString()
                    } else {
                        versionOut = context.readFile('workspace/target/metadata/version.txt')
                    }

                    versionInfo = parseVersionOutput(versionOut)
                }

                writeMetadata(versionInfo, true)
            } finally {
                // Always archive any artifacts including failed make logs..
                try {
                    context.timeout(time: buildTimeouts.BUILD_ARCHIVE_TIMEOUT, unit: 'HOURS') {
                        // We have already archived cross compiled artifacts, so only archive the metadata files
                        if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
                            context.println '[INFO] Archiving JSON Files...'
                            context.archiveArtifacts artifacts: 'workspace/target/*.json'
                        } else {
                            context.archiveArtifacts artifacts: 'workspace/target/*'
                        }
                    }

                    //Archive to Artifactory
                    context.timeout(time: buildTimeouts.BUILD_ARCHIVE_TIMEOUT, unit: "HOURS") {
                        def artifactoryRepo = "sys-rt-generic-local"
                        def artifactoryUploadDir = "${artifactoryRepo}/hyc-runtimes-jenkins.swg-devops.com/${context.JOB_NAME}/${context.BUILD_ID}/"
                        def artifactPattern = "workspace/target/*"
                        if (buildConfig.BUILD_ARGS.contains('--cross-compile')) {
                            artifactPattern = "workspace/target/*.json"
                        }
                        def uploadSpec = [["pattern": artifactPattern,
                                       "target": artifactoryUploadDir
                                    ]]
                        def uploadFiles = [files : uploadSpec]
                        def uploadJSON = JsonOutput.toJson(uploadFiles)

                        def server = context.Artifactory.server "na-public.artifactory.swg-devops"

                        def buildInfo = context.Artifactory.newBuildInfo()
                        // 200 artifacts is >9months for 5 builds/week (more for non-lts releases that run overy-other day)
                        // 300 days is >9months
                        buildInfo.retention maxBuilds: 200, maxDays: 300, deleteBuildArtifacts: true
                        // Change Artifactory build name to conform to TaaS rules
                        // See infrastructure#7361 for more details
                        buildInfo.name = "sys-rt/${context.JOB_NAME}"

                        server.upload spec: uploadJSON, buildInfo: buildInfo
                        server.publishBuildInfo buildInfo

                        artifactoryBaseUrl = server.getUrl() + '/' + artifactoryRepo
                        artifactoryCredential = server.getCredentialsId()

                        if (buildInfo.getArtifacts().size() > 0) {
                            for (def artifact in buildInfo.getArtifacts()) {
                                if ((artifact.getRemotePath().contains(".tar.gz") || artifact.getRemotePath().contains(".zip")) && !artifact.getRemotePath().contains(".json")) {
                                    def artifactUrl = artifactoryBaseUrl + '/' + artifact.getRemotePath()
                                    def artifactName = artifactUrl.substring(artifactUrl.lastIndexOf("/") + 1);
                                    if (artifactUrl.contains("-jdk_")) {
                                        jdkUrl = artifactUrl
                                    } else if (artifactUrl.contains("-jre_")) {
                                        jreUrl = artifactUrl
                                    } else if (artifactUrl.contains("-testimage_")) {
                                        testimageUrl = artifactUrl
                                    }
                                    context.currentBuild.description += "<br><a href=${artifactUrl}>${artifactName}</a>"
                                }
                            }
                        } else {
                            context.println "Appears nothing will be uploaded to Artifactory"
                        }

                    }
                } catch (FlowInterruptedException e) {
                    // Set Github Commit Status
                    if (env.JOB_NAME.contains('pr-tester')) {
                        updateGithubCommitStatus('FAILED', 'Build FAILED')
                    }
                    throw new Exception("[ERROR] Build archive timeout (${buildTimeouts.BUILD_ARCHIVE_TIMEOUT} HOURS) has been reached. Exiting...")
                }

                // post-build workspace clean:
                if (cleanWorkspaceAfter || cleanWorkspaceBuildOutputAfter) {
                    try {
                        context.timeout(time: buildTimeouts.NODE_CLEAN_TIMEOUT, unit: 'HOURS') {
                            // Note: Underlying org.apache DirectoryScanner used by cleanWs has a bug scanning where it misses files containing ".." so use rm -rf instead
                            // Issue: https://issues.jenkins.io/browse/JENKINS-64779
                            if (context.WORKSPACE != null && !context.WORKSPACE.isEmpty()) {
                                if (cleanWorkspaceAfter) {
                                    context.println 'Cleaning workspace non-hidden files: ' + context.WORKSPACE + '/*'
                                    context.sh(script: 'rm -rf ' + context.WORKSPACE + '/*')

                                    // Clean remaining hidden files using cleanWs
                                    try {
                                        context.println 'Cleaning workspace hidden files using cleanWs: ' + context.WORKSPACE
                                        context.cleanWs notFailBuild: true, disableDeferredWipeout: true, deleteDirs: true
                                    } catch (e) {
                                        context.println "Failed to clean ${e}"
                                    }
                                } else if (cleanWorkspaceBuildOutputAfter) {
                                    context.println 'Cleaning workspace build output files: ' + context.WORKSPACE + '/workspace/build/src/build'
                                    context.sh(script: 'rm -rf ' + context.WORKSPACE + '/workspace/build/src/build')
                                    context.println 'Cleaning workspace build output files: ' + context.WORKSPACE + '/workspace/target'
                                    context.sh(script: 'rm -rf ' + context.WORKSPACE + '/workspace/target')
                                }
                            } else {
                                context.println 'Warning: Unable to clean workspace as context.WORKSPACE is null/empty'
                            }
                        }
                    } catch (FlowInterruptedException e) {
                        // Set Github Commit Status
                        if (env.JOB_NAME.contains('pr-tester')) {
                            updateGithubCommitStatus('FAILED', 'Build FAILED')
                        }
                        throw new Exception("[ERROR] AIX clean workspace timeout (${buildTimeouts.AIX_CLEAN_TIMEOUT} HOURS) has been reached. Exiting...")
                    }
                }
                // Set Github Commit Status
                if (env.JOB_NAME.contains('pr-tester')) {
                    updateGithubCommitStatus('SUCCESS', 'Build PASSED')
                }
            }
        }
    }

    /*
    Pulls in and applies the activeNodeTimeout parameter.
    The function will use the jenkins helper nodeIsOnline lib to check once a minute if a node with the specified label has come online.
    If it doesn't find one or the timeout is set to 0 (default), it'll crash out. Otherwise, it'll return and jump onto the node.
    */
    def waitForANodeToBecomeActive(def label) {
        String helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
        def NodeHelper = context.library(identifier: "openjdk-jenkins-helper@${helperRef}").NodeHelper

        // If label contains mac skip waiting for node to become active as we use Orka
        if (label.contains('mac')) {
            return
        }

        // A node with the requested label is ready to go
        if (NodeHelper.nodeIsOnline(label)) {
            return
        }

        context.println('No active node matches this label: ' + label)

        // Import activeNodeTimeout param
        int activeNodeTimeout = 0
        if (buildConfig.ACTIVE_NODE_TIMEOUT.isInteger()) {
            activeNodeTimeout = buildConfig.ACTIVE_NODE_TIMEOUT as Integer
        }

        if (activeNodeTimeout > 0) {
            context.println('Will check again periodically until a node labelled ' + label + ' comes online, or ' + buildConfig.ACTIVE_NODE_TIMEOUT + ' minutes (ACTIVE_NODE_TIMEOUT) has passed.')
            int x = 0
            while (x < activeNodeTimeout) {
                context.sleep(time: 1, unit: 'MINUTES')
                if (NodeHelper.nodeIsOnline(label)) {
                    context.println('A node which matches this label is now active: ' + label)
                    return
                }
                x++
            }
            throw new Exception('No node matching this label became active prior to the timeout: ' + label)
        } else {
            throw new Exception('As the timeout value is set to 0, we will not wait for a node to become active.')
        }
    }

    /* 
        this function should only be used in pr-tester
    */
    def updateGithubCommitStatus(STATE, MESSAGE) {
        // workaround https://issues.jenkins-ci.org/browse/JENKINS-38674
        // get repourl from job's DEFAULTS_JSON  points to upstream repo
        String repoUrl = DEFAULTS_JSON['repository']['pipeline_url'] // USER_REMOTE_CONFIGS['remotes']['url']
        // get branch/commit SHA1 from job's USER_REMOTE_CONFIGS which is the commits from PR
        Map paramUserRemoteConfigs = new JsonSlurper().parseText(context.USER_REMOTE_CONFIGS)
        String commitSha = paramUserRemoteConfigs['branch']

        String shortJobName = env.JOB_NAME.split('/').last()

        context.println 'Setting GitHub Checks Status:'
        context.println "REPO URL: ${repoUrl}"
        context.println "COMMIT SHA: ${commitSha}"
        context.println "STATE: ${STATE}"
        context.println "MESSAGE: ${MESSAGE}"
        context.println "JOB NAME: ${shortJobName}"

        context.step([
            $class: 'GitHubCommitStatusSetter',
            reposSource: [$class: 'ManuallyEnteredRepositorySource', url: repoUrl],
            commitShaSource: [$class: 'ManuallyEnteredShaSource', sha: commitSha],
            contextSource: [$class: 'ManuallyEnteredCommitContextSource', context: shortJobName],
            errorHandlers: [[$class: 'ChangingBuildStatusErrorHandler', result: 'UNSTABLE']],
            statusResultSource: [$class: 'ConditionalStatusResultSource', results: [[$class: 'AnyBuildResult', message: MESSAGE, state: STATE]] ]
        ])
    }

    def addNodeToBuildDescription() {
        // Append to existing build description if not null
        def tmpDesc = (context.currentBuild.description) ? context.currentBuild.description + '<br>' : ''
        context.currentBuild.description = tmpDesc + "<a href=${context.JENKINS_URL}computer/${context.NODE_NAME}>${context.NODE_NAME}</a>"
    }

    /*
    Main function. This is what is executed remotely via the helper file kick_off_build.groovy, which is in turn executed by the downstream jobs.
    Running in downstream build job jdk-*-*-* called by kick_off_build.groovy
    */
    @SuppressWarnings('unused')
    def build() {
        context.timestamps {
            try {
                context.println 'Build config (BUILD_CONFIGURAION):'
                context.println buildConfig.toJson()

                def filename = determineFileName()

                context.println "Executing tests: ${buildConfig.TEST_LIST}"
                context.println "Build num: ${env.BUILD_NUMBER}"
                context.println "File name: ${filename}"

                def enableReproducibleCompare = Boolean.valueOf(buildConfig.ENABLE_REPRODUCIBLE_COMPARE)
                def enableTests = Boolean.valueOf(buildConfig.ENABLE_TESTS)
                def enableInstallers = Boolean.valueOf(buildConfig.ENABLE_INSTALLERS)
                def enableSigner = Boolean.valueOf(buildConfig.ENABLE_SIGNER)
                def enableTCK = Boolean.valueOf(buildConfig.RELEASE) || Boolean.valueOf(buildConfig.WEEKLY)
                def useAdoptShellScripts = Boolean.valueOf(buildConfig.USE_ADOPT_SHELL_SCRIPTS)
                def cleanWorkspace = Boolean.valueOf(buildConfig.CLEAN_WORKSPACE)
                def cleanWorkspaceAfter = Boolean.valueOf(buildConfig.CLEAN_WORKSPACE_AFTER)
                def cleanWorkspaceBuildOutputAfter = Boolean.valueOf(buildConfig.CLEAN_WORKSPACE_BUILD_OUTPUT_ONLY_AFTER)
                // Get branch/tag of temurin-build, ci-jenkins-pipeline and jenkins-helper repo from BUILD_CONFIGURATION or defaultsJson
                def helperRef = buildConfig.HELPER_REF ?: DEFAULTS_JSON['repository']['helper_ref']
                def nonDockerNodeName = ''

                context.stage('queue') {
                    /* This loads the library containing two Helper classes, and causes them to be
                    imported/updated from their repo. Without the library being imported here, runTests method will fail to execute the post-build test jobs for reasons unknown.*/
                    context.library(identifier: "openjdk-jenkins-helper@${helperRef}")

                    // Set Github Commit Status
                    if (env.JOB_NAME.contains('pr-tester')) {
                        context.node('worker') {
                            updateGithubCommitStatus('PENDING', 'Pending')
                        }
                    }

                    if (buildConfig.DOCKER_IMAGE) {
                        // Docker build environment
                        def label = buildConfig.NODE_LABEL + '&&dockerBuild'
                        if (buildConfig.DOCKER_NODE) {
                            label = buildConfig.NODE_LABEL + '&&' + "$buildConfig.DOCKER_NODE"
                        }

                        if (buildConfig.CODEBUILD) {
                            label = 'codebuild'
                        }
                        context.println "[NODE SHIFT] MOVING INTO DOCKER NODE MATCHING LABELNAME ${label}..."
                        if ( ! ( "${buildConfig.DOCKER_IMAGE}" ==~ /^[A-Za-z0-9\/\.\-_:]*$/ ) ||
                             ! ( "${buildConfig.DOCKER_ARGS}"  ==~ /^[A-Za-z0-9\/\.\-_=\ ]*$/ ) ) {
                             throw new Exception("[ERROR] Dubious characters in DOCKER* image or parameters: ${buildConfig.DOCKER_IMAGE} ${buildConfig.DOCKER_ARGS} - aborting");
                        }
                        context.node(label) {
                            addNodeToBuildDescription()
                            // Cannot clean workspace from inside docker container
                            if (cleanWorkspace) {
                                try {
                                    context.timeout(time: buildTimeouts.CONTROLLER_CLEAN_TIMEOUT, unit: 'HOURS') {
                                        // Cannot clean workspace from inside docker container
                                        if (cleanWorkspace) {
                                            try {
                                                context.cleanWs notFailBuild: true
                                            } catch (e) {
                                                context.println "Failed to clean ${e}"
                                            }
                                            cleanWorkspace = false
                                        }
                                    }
                                } catch (FlowInterruptedException e) {
                                    throw new Exception("[ERROR] Controller clean workspace timeout (${buildTimeouts.CONTROLLER_CLEAN_TIMEOUT} HOURS) has been reached. Exiting...")
                                }
                            }
                            if (!("${buildConfig.DOCKER_IMAGE}".contains('rhel'))) {
                                // Pull the docker image from DockerHub
                                try {
                                    context.timeout(time: buildTimeouts.DOCKER_PULL_TIMEOUT, unit: 'HOURS') {
                                        if (buildConfig.DOCKER_CREDENTIAL) {
                                            context.docker.withRegistry(buildConfig.DOCKER_REGISTRY, buildConfig.DOCKER_CREDENTIAL) {
                                                if (buildConfig.DOCKER_ARGS) {
                                                    context.sh(script: "docker pull ${buildConfig.DOCKER_IMAGE} ${buildConfig.DOCKER_ARGS}")
                                                } else {
                                                    context.docker.image(buildConfig.DOCKER_IMAGE).pull()
                                                }
                                            }
                                        } else {
                                            if (buildConfig.DOCKER_ARGS) {
                                                context.sh(script: "docker pull ${buildConfig.DOCKER_IMAGE} ${buildConfig.DOCKER_ARGS}")
                                            } else {
                                                context.docker.image(buildConfig.DOCKER_IMAGE).pull()
                                            }
                                        }
                                        // Store the pulled docker image digest as 'buildinfo'
                                        dockerImageDigest = context.sh(script: "docker inspect --format='{{.RepoDigests}}' ${buildConfig.DOCKER_IMAGE}", returnStdout:true)
                                    }
                                } catch (FlowInterruptedException e) {
                                    throw new Exception("[ERROR] Controller docker image pull timeout (${buildTimeouts.DOCKER_PULL_TIMEOUT} HOURS) has been reached. Exiting...")
                                }
                            }

                            // Use our dockerfile if DOCKER_FILE is defined
                            if (buildConfig.DOCKER_FILE) {
                                try {
                                    context.timeout(time: buildTimeouts.DOCKER_CHECKOUT_TIMEOUT, unit: 'HOURS') {
                                        def repoHandler = new RepoHandler(USER_REMOTE_CONFIGS, ADOPT_DEFAULTS_JSON, buildConfig.CI_REF, buildConfig.BUILD_REF)
                                        repoHandler.setUserDefaultsJson(context, DEFAULTS_JSON)
                                        if (useAdoptShellScripts) {
                                            repoHandler.checkoutAdoptPipelines(context)
                                        } else {
                                            repoHandler.checkoutUserPipelines(context)
                                        }

                                        // Perform a git clean outside of checkout to avoid the Jenkins enforced 10 minute timeout
                                        // https://github.com/adoptium/infrastucture/issues/1553
                                        context.sh(script: 'git clean -fdx')

                                        printGitRepoInfo()
                                    }
                                } catch (FlowInterruptedException e) {
                                    throw new Exception("[ERROR] Controller docker file scm checkout timeout (${buildTimeouts.DOCKER_CHECKOUT_TIMEOUT} HOURS) has been reached. Exiting...")
                                }

                                if (buildConfig.DOCKER_CREDENTIAL) {
                                    context.docker.withRegistry(buildConfig.DOCKER_REGISTRY, buildConfig.DOCKER_CREDENTIAL) {
                                        context.docker.build("build-image", "--build-arg image=${buildConfig.DOCKER_IMAGE} -f ${buildConfig.DOCKER_FILE} .").inside(buildConfig.DOCKER_ARGS) {
                                            buildScripts(
                                                cleanWorkspace,
                                                cleanWorkspaceAfter,
                                                cleanWorkspaceBuildOutputAfter,
                                                filename,
                                                useAdoptShellScripts
                                            )
                                        }
                                    }
                                }
                            } else {
                                dockerImageDigest = dockerImageDigest.replaceAll("\\[", "").replaceAll("\\]", "")
                                String dockerRunArg="-e \"BUILDIMAGESHA=$dockerImageDigest\""

                                // Are we running podman in Docker CLI Emulation mode?
                                def isPodman = context.sh(script: "docker --version | grep podman", returnStatus:true)
                                if (isPodman == 0) {
                                    // Note: --userns was introduced in podman 4.3.0
                                    // Add uid and gid userns mapping required for podman
                                    dockerRunArg += " --userns keep-id:uid=1000,gid=1000"
                                }
                                context.docker.image(buildConfig.DOCKER_IMAGE).inside(buildConfig.DOCKER_ARGS+" "+dockerRunArg) {
                                    buildScripts(
                                        cleanWorkspace,
                                        cleanWorkspaceAfter,
                                        cleanWorkspaceBuildOutputAfter,
                                        filename,
                                        useAdoptShellScripts
                                    )
                                }
                            }
                        }
                        context.println "[NODE SHIFT] OUT OF DOCKER NODE (LABELNAME ${label}!)"

                    // Build the jdk outside of docker container...
                    } else {
                        waitForANodeToBecomeActive(buildConfig.NODE_LABEL)
                        context.println "[NODE SHIFT] MOVING INTO JENKINS NODE MATCHING LABELNAME ${buildConfig.NODE_LABEL}..."
                        context.node(buildConfig.NODE_LABEL) {
                            addNodeToBuildDescription()
                            nonDockerNodeName = context.NODE_NAME
                            // This is to avoid windows path length issues.
                            context.echo("checking ${buildConfig.TARGET_OS}")
                            if (buildConfig.TARGET_OS == 'windows') {
                                // See https://github.com/adoptium/infrastucture/issues/1284#issuecomment-621909378 for justification of the below path
                                def workspace = 'C:/workspace/openjdk-build/'
                                if (env.CYGWIN_WORKSPACE) {
                                    workspace = env.CYGWIN_WORKSPACE
                                }
                                context.echo("changing ${workspace}")
                                context.ws(workspace) {
                                    buildScripts(
                                        cleanWorkspace,
                                        cleanWorkspaceAfter,
                                        cleanWorkspaceBuildOutputAfter,
                                        filename,
                                        useAdoptShellScripts
                                    )
                                }
                            } else {
                                buildScripts(
                                    cleanWorkspace,
                                    cleanWorkspaceAfter,
                                    cleanWorkspaceBuildOutputAfter,
                                    filename,
                                    useAdoptShellScripts
                                )
                            }
                        }
                        context.println "[NODE SHIFT] OUT OF JENKINS NODE (LABELNAME ${buildConfig.NODE_LABEL}!)"
                    }
                }

                // Sign and archive jobs if needed
                if (enableSigner) {
                    try {
                        // Sign job timeout managed by Jenkins job config
                        sign(versionInfo)
                    } catch (FlowInterruptedException e) {
                        throw new Exception("[ERROR] Sign job timeout (${buildTimeouts.SIGN_JOB_TIMEOUT} HOURS) has been reached OR the downstream sign job failed. Exiting...")
                    }
                }

                // Run Smoke Tests and AQA Tests
                if (enableTests) {
                    try {
                        //Only smoke tests succeed TCK and AQA tests will be triggerred.
                        if (runSmokeTests() == 'SUCCESS') {
                            // Remote trigger Eclipse Temurin JCK tests
                            if (buildConfig.VARIANT == 'temurin' && enableTCK) {
                                def platform = ''
                                if (buildConfig.ARCHITECTURE.contains('x64')) {
                                    platform = 'x86-64_' + buildConfig.TARGET_OS
                                } else {
                                    platform = buildConfig.ARCHITECTURE + '_' + buildConfig.TARGET_OS
                                }           
                                if ( !(platform  == 'riscv64_linux' || platform =='aarch64_windows') ) {
                                    if ( !(buildConfig.JAVA_TO_BUILD == 'jdk8u' && platform == 's390x_linux') ) {
                                        context.echo "Remote trigger Eclipse Temurin AQA_Test_Pipeline job with ${platform} ${buildConfig.JAVA_TO_BUILD}"
                                        def remoteTargets = remoteTriggerJckTests(platform, filename)
                                        context.parallel remoteTargets
                                    }
                                }
                            }

                            if (buildConfig.TEST_LIST.size() > 0) {
                                def testStages = runAQATests()
                                context.parallel testStages
                            }
                        } else {
                            context.println('[ERROR]Smoke tests are not successful! AQA and Tck tests are blocked ')
                        }
                    } catch (Exception e) {
                        context.println(e.message)
                    }
                }

                //buildInstaller if needed
                if (enableInstallers) {
                    try {
                        // Installer job timeout managed by Jenkins job config
                        buildInstaller(versionInfo)
                        // sing installers for all variants except openj9
                        // openj9 variant builds sign msi during the creation and pkg is 3rd party signed atm.
                        if ((buildConfig.VARIANT != "openj9") || ((buildConfig.VARIANT == "openj9") && (buildConfig.TARGET_OS == "aix"))){
                            signInstaller(versionInfo)
                        }
                    } catch (FlowInterruptedException e) {
                        throw new Exception("[ERROR] Installer job timeout (${buildTimeouts.INSTALLER_JOBS_TIMEOUT} HOURS) has been reached OR the downstream installer job failed. Exiting...")
                    }
                }
                if (!env.JOB_NAME.contains('pr-tester')) {
                    try {
                        gpgSign()
                    } catch (Exception e) {
                        context.println(e.message)
                    }
                }

                if (!env.JOB_NAME.contains('pr-tester')) { // pr-tester does not sign the binaries
                    // Verify Windows and Mac Signing for Temurin
                    if (buildConfig.VARIANT == 'temurin') {
                        try {
                            verifySigning()
                        } catch (Exception e) {
                            context.println(e.message)
                        }
                    }
                }

                // Compare reproducible build if needed
                if (enableReproducibleCompare) {
                    compareReproducibleBuild(nonDockerNodeName)
                }

            // Generic catch all. Will usually be the last message in the log.
            } catch (Exception e) {
                currentBuild.result = 'FAILURE'
                context.println "Execution error: ${e}"
                def sw = new StringWriter()
                def pw = new PrintWriter(sw)
                e.printStackTrace(pw)
                context.println sw.toString()
            }
        }
    }

}

return {
    buildConfigArg,
    USER_REMOTE_CONFIGS,
    DEFAULTS_JSON,
    ADOPT_DEFAULTS_JSON,
    context,
    env,
    currentBuild ->
    def buildConfig
    if (String.isInstance(buildConfigArg)) {
        buildConfig = new IndividualBuildConfig(buildConfigArg as String)
        } else {
        buildConfig = buildConfigArg as IndividualBuildConfig
    }

    return new Build(
            buildConfig,
            USER_REMOTE_CONFIGS,
            DEFAULTS_JSON,
            ADOPT_DEFAULTS_JSON,
            context,
            env,
            currentBuild
        )
}
