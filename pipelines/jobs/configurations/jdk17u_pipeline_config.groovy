class Config17 {

    final Map<String, Map<String, ?>> buildConfigurations = [
        x64Mac    : [
                os                  : 'mac',
                arch                : 'x64',
                additionalNodeLabels: 'ci.project.openj9 && hw.arch.x86 && sw.os.mac.10_15',
                additionalTestLabels: [
                        openj9      : '!sw.os.mac.10_11'
                ],
                test                : 'default',
                cleanWorkspaceAfterBuild: true,
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'      : '--create-jre-image',
                        'temurin'     : '--create-jre-image --create-sbom'
                ],
                configureArgs       : '--enable-dtrace --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
        ],

        x64Linux  : [
                os                  : 'linux',
                arch                : 'x64',
                additionalNodeLabels : 'ci.project.openj9 && hw.arch.x86 && sw.os.linux',
                dockerImage: [
                        temurin     : 'adoptopenjdk/centos6_build_image',
                        openj9      : 'adoptopenjdk/centos7_build_image'
                ],
                dockerFile: [
                        openj9  : 'pipelines/build/dockerFiles/cuda.dockerfile'
                ],
                dockerNode          : 'sw.tool.docker && sw.config.uid1000',
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                test                : 'default',
                cleanWorkspaceAfterBuild: true,
                reproducibleCompare : [
                        'temurin'   : true
                ],
                additionalTestLabels: [
                        openj9      : '!(sw.os.cent.6||sw.os.rhel.6)'
                ],
                configureArgs       : [
                        'openj9'      : '--enable-dtrace --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"',
                        'temurin'     : '--enable-dtrace'
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-source-archive --create-jre-image --create-sbom'
                ]
        ],

        x64AlpineLinux  : [
                os                  : 'alpine-linux',
                arch                : 'x64',
                dockerImage         : 'adoptopenjdk/alpine3_build_image',
                test                : 'default',
                configureArgs       : '--enable-headless-only=yes',
                buildArgs           : [
                        'temurin'   : '--create-jre-image --create-sbom'
                ]
        ],

        aarch64AlpineLinux  : [
                os                  : 'alpine-linux',
                arch                : 'aarch64',
                dockerImage         : 'adoptopenjdk/alpine3_build_image',
                test                : 'default',
                configureArgs       : '--enable-headless-only=yes',
                buildArgs           : [
                        'temurin'   : '--create-jre-image --create-sbom'
                ]
        ],

        x64Windows: [
                os                  : 'windows',
                arch                : 'x64',
                additionalNodeLabels: [
                        openj9 : 'ci.project.openj9 && hw.arch.x86 && sw.os.windows',
                        temruin : 'win2022&&vs2019'
                ],
                cleanWorkspaceAfterBuild: true,
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-jre-image --create-sbom'
                ],
                configureArgs: '--with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition" --with-jdk-rc-name="IBM Semeru Runtime"',
                test                : 'default'
        ],

        aarch64Windows: [
                os                  : 'windows',
                arch                : 'aarch64',
                crossCompile        : 'x64',
                additionalNodeLabels: 'win2016&&vs2019',
                test                : false,
                buildArgs       : [
                        'temurin'   : '--create-jre-image --create-sbom --cross-compile'
                ]
        ],

        x32Windows: [
                os                  : 'windows',
                arch                : 'x86-32',
                additionalNodeLabels: 'win2022&&vs2019',
                test                : 'default',
                configureArgs       : "--with-ucrt-dll-dir='C:/progra~2/wi3cf2~1/10/Redist/10.0.22000.0/ucrt/DLLs/x86'",
                buildArgs           : [
                        'temurin'   : '--jvm-variant client,server --create-jre-image --create-sbom'
                ]
        ],

        ppc64Aix    : [
                os                  : 'aix',
                arch                : 'ppc64',
                additionalNodeLabels: [
                        temurin: 'xlc13&&aix720',
                        openj9:  'hw.arch.ppc64 && sw.os.aix.7_2'
                ],
                test                : 'default',
                additionalTestLabels:  [
                        temurin: 'sw.os.aix.7_2'
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-jre-image --create-sbom'
                ],
                configureArgs : [
                        openj9: '--disable-ccache --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
                ],
                cleanWorkspaceAfterBuild: true
        ],

        s390xLinux    : [
                os                  : 'linux',
                arch                : 's390x',
                test                : 'default',
                cleanWorkspaceAfterBuild: true,
                additionalNodeLabels: [
                        openj9:  'hw.arch.s390x && (sw.os.cent.7 || sw.os.rhel.7)'
                ],
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'      : '--create-jre-image',
                        'temurin'   : '--create-jre-image --create-sbom'
                ],
                configureArgs       : '--enable-dtrace --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
        ],

        ppc64leLinux    : [
                os                  : 'linux',
                arch                : 'ppc64le',
                test                : 'default',
                cleanWorkspaceAfterBuild: true,
                additionalNodeLabels: [
                        openj9:  'hw.arch.ppc64le && (sw.os.cent.7 || sw.os.rhel.7)'
                ],
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-jre-image'
                ],
                configureArgs       : [
                        'openj9'      : '--with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
                ]
        ],

        aarch64Linux    : [
                os                  : 'linux',
                arch                : 'aarch64',
                dockerImage         : 'adoptopenjdk/centos7_build_image',
                dockerNode         : 'sw.tool.docker',
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                test                : 'default',
                additionalNodeLabels: [
                        openj9:  'hw.arch.aarch64 && sw.os.linux'
                ],
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-jre-image --create-sbom'
                ],
                configureArgs       : '--enable-dtrace  --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
        ],

        aarch64Mac: [
                os                  : 'mac',
                arch                : 'aarch64',
                additionalNodeLabels: [
                        temurin : 'xcode15.0.1',
                        openj9 : 'ci.project.openj9 && hw.arch.aarch64 && sw.os.mac'
                ],
                cleanWorkspaceAfterBuild: true,
                configureArgs       : [
                        openj9      : '--enable-dtrace --disable-warnings-as-errors --with-noncompressedrefs --with-product-name="IBM Semeru Runtime" --with-product-suffix="Open Edition"'
                ],
                test                : [
                        temurin : 'default',
                        openj9 : 'default'
                ],
                reproducibleCompare : [
                        'temurin'   : true
                ],
                buildArgs           : [
                        'openj9'    : '--create-jre-image',
                        'temurin'   : '--create-jre-image --create-sbom'
                ]
        ],

        arm32Linux    : [
                os                  : 'linux',
                arch                : 'arm',
                crossCompile        : 'aarch64',
                dockerImage         : 'adoptopenjdk/ubuntu1604_build_image',
                dockerArgs          : '--platform linux/arm/v7',
                test                : 'default',
                configureArgs       : '--enable-dtrace',
                buildArgs           : [
                        'temurin'   : '--create-jre-image --create-sbom'
                ]
        ],

        riscv64Linux      :  [
                os                  : 'linux',
                arch                : 'riscv64',
                crossCompile        : 'dockerhost-rise-ubuntu2204-aarch64-1',
                dockerImage         : 'adoptopenjdk/ubuntu2004_build_image:linux-riscv64',
                dockerArgs          : '--platform linux/riscv64',
                test                : 'default',
                configureArgs       : '--enable-headless-only=yes --enable-dtrace',
                buildArgs           : [
                        'temurin'   : '--create-jre-image --create-sbom'
                ]
        ],

        aarch64Windows: [
                os                  : 'windows',
                arch                : 'aarch64',
                crossCompile        : 'x64',
                additionalNodeLabels: 'win2022&&vs2019',
                test                : 'default',
                buildArgs       : [
                        'temurin'   : '--create-jre-image --create-sbom --cross-compile'
                ]
        ],

        x64MacIBM    : [
                os                  : 'mac',
                arch                : 'x64',
                additionalNodeLabels : 'ci.project.openj9 && hw.arch.x86 && sw.os.mac.10_15',
                test                : 'default',
                cleanWorkspaceAfterBuild: true,
                configureArgs       : [
                        'openj9'      : '--enable-dtrace'
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
        ],

        x64LinuxIBM  : [
                os                  : 'linux',
                arch                : 'x64',
                additionalNodeLabels : 'ci.project.openj9 && hw.arch.x86 && sw.os.linux',
                dockerImage         : 'adoptopenjdk/centos7_build_image',
                dockerFile: [
                        'openj9'  : 'pipelines/build/dockerFiles/cuda.dockerfile'
                ],
                dockerNode          : 'sw.tool.docker && sw.config.uid1000',
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                test                : [
                        nightly: [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'special.system'
                        ],
                        weekly : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.functional',
                                'sanity.external',
                                'dev.external',
                                'dev.openjdk',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'                        
                        ],
                        release : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.external',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'
                        ]
                ],
                additionalTestLabels: [
                        openj9      : '!(centos6||rhel6)'
                ],
                configureArgs       : [
                        'openj9'      : '--enable-dtrace'
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
        ],

        x64WindowsIBM: [
                os                  : 'windows',
                arch                : 'x64',
                additionalNodeLabels: [
                        openj9:     'ci.project.openj9 && hw.arch.x86 && sw.os.windows'
                ],
                buildArgs : [
                        'openj9' : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
                ],
                test                : 'default',
                configureArgs       : [
                        'openj9'      : '--with-jdk-rc-name="IBM Semeru Runtime"'
                ],
                additionalFileNameTag: 'IBM'
        ],

        ppc64AixIBM    : [
                os                  : 'aix',
                arch                : 'ppc64',
                additionalNodeLabels: [
                        openj9:  'hw.arch.ppc64 && sw.os.aix.7_2'
                ],
                test                : 'default',
                configureArgs       : [
                        'openj9'      : '--disable-ccache'
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
        ],

        s390xLinuxIBM    : [
                os                  : 'linux',
                arch                : 's390x',
                test                : [
                        nightly: [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'special.system'
                        ],
                        weekly : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.functional',
                                'sanity.external',
                                'dev.external',
                                'dev.openjdk',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'                        
                        ],
                        release : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.external',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'
                        ]
                ],
                additionalNodeLabels: [
                        openj9:  'hw.arch.s390x && (sw.os.cent.7 || sw.os.rhel.7)'
                ],
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                configureArgs       : '--enable-dtrace',
                additionalFileNameTag: 'IBM',
                buildArgs : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
        ],

        ppc64leLinuxIBM    : [
                os                  : 'linux',
                arch                : 'ppc64le',
                test                : [
                        nightly: [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'special.system'
                        ],
                        weekly : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.functional',
                                'sanity.external',
                                'dev.external',
                                'dev.openjdk',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'                        
                        ],
                        release : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.external',
                                'sanity.functional.fips140_2',
                                'extended.functional.fips140_2',
                                'sanity.jck.fips140_2',
                                'extended.jck.fips140_2',
                                'special.jck.fips140_2',
                                'sanity.openjdk.fips140_2',
                                'extended.openjdk.fips140_2'
                        ]
                ],
                additionalNodeLabels: [
                        openj9:  'hw.arch.ppc64le && (sw.os.cent.7 || sw.os.rhel.7)'
                ],
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                configureArgs       : [
                        'openj9'      : ''
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
        ],

        aarch64LinuxIBM    : [
                os                  : 'linux',
                arch                : 'aarch64',
                dockerImage         : 'adoptopenjdk/centos7_build_image',
                dockerNode         : 'sw.tool.docker',
                dockerCredential    : '9f50c848-8764-440d-b95a-1d295c21713e',
                test                : [
                        nightly: [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'special.system'
                        ],
                        weekly : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.functional',
                                'sanity.external',
                                'dev.external',
                                'dev.openjdk'                        
                        ],
                        release : [
                                'sanity.functional',
                                'sanity.openjdk',
                                'sanity.perf',
                                'sanity.jck',
                                'sanity.system',
                                'extended.functional',
                                'extended.openjdk',
                                'extended.perf',
                                'extended.jck',
                                'extended.system',
                                'special.functional',
                                'special.jck',
                                'special.openjdk',
                                'special.system',
                                'dev.external'
                        ]
                ],
                additionalNodeLabels: [
                        openj9:  'hw.arch.aarch64 && sw.os.linux'
                ],
                configureArgs       : [
                        openj9      : '--enable-dtrace  '
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : [
                        'openj9'    : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
                ]
        ],

        aarch64MacIBM: [
                os                  : 'mac',
                arch                : 'aarch64',
                additionalNodeLabels: [
                        temurin : 'macos11',
                        openj9 : 'ci.project.openj9 && hw.arch.aarch64 && sw.os.mac'
                ],
                cleanWorkspaceAfterBuild: true,
                configureArgs       : [
                        openj9      : '--enable-dtrace --disable-warnings-as-errors --with-noncompressedrefs'
                ],
                test                : [
                        openj9 : 'default'
                ],
                additionalFileNameTag: 'IBM',
                buildArgs : [
                        'openj9'    : '--ssh --disable-adopt-branch-safety -r git@github.ibm.com:runtimes/openj9-openjdk-jdk17 -b ibm_sdk --create-jre-image'
                ]
        ]
  ]

}

Config17 config = new Config17()
return config.buildConfigurations
