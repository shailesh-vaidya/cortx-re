#!/usr/bin/env groovy
// Please configure branch and os_version as string parameters in Jenkins configuration manually.
pipeline {
    agent {
        node {
            label "docker-${os_version}-node"
        }
    }

    triggers {
        pollSCM '*/5 * * * *'
    }
    
    parameters {
        choice(
            choices: ['libfabric' , 'lustre'],
            description: '',
            name: 'TRANSPORT')
    }
        
    environment {
        version = "2.0.0"    
        env = "dev"
        component = "motr"
        release_dir = "/mnt/bigstorage/releases/cortx"
        build_upload_dir = "$release_dir/components/github/$branch/$os_version/$env/$component"

        // Dependent component job build
        build_upload_dir_s3_dev = "$release_dir/components/github/$branch/$os_version/$env/s3server"
        build_upload_dir_hare = "$release_dir/components/github/$branch/$os_version/$env/hare"
    }
    
    options {
        timeout(time: 300, unit: 'MINUTES')
        timestamps()
        ansiColor('xterm')
        disableConcurrentBuilds()  
    }

    stages {
        stage('Checkout') {
            steps {
                script { build_stage = env.STAGE_NAME }
                dir ('motr') {
                    checkout([$class: 'GitSCM', branches: [[name: "*/${branch}"]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'AuthorInChangelog'], [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'cortx-admin-github', url: 'https://github.com/Seagate/cortx-motr']]])
                }
            }
        }
        
    stage('Check TRANSPORT module') {
            steps {
                script { build_stage = env.STAGE_NAME }
                dir ('motr') {
                    sh label: '', script: '''
                        if [[ "$TRANSPORT" == "libfabric" ]]; then
                            echo "We are using default 'libfabric' module/package"
                        else
                            sed -i '/libfabric/d' cortx-motr.spec.in
                            echo "Removed libfabric from spec file as we are going to use $TRANSPORT"
                         fi
                    '''
                }
            }
        }

        stage('Install Dependencies') {
            steps {
                script { build_stage = env.STAGE_NAME }
                dir ('motr') {
                    
                    sh label: '', script: '''
                    yum-config-manager --add-repo=http://cortx-storage.colo.seagate.com/releases/cortx/third-party-deps/rockylinux/rockylinux-8.4-2.0.0-latest/
                    yum --nogpgcheck -y --disablerepo="EOS_Rocky_8_OS_x86_64_Rocky_8" install libfabric-1.11.2 libfabric-devel-1.11.2 texlive mscgen
                    '''

                    sh label: '', script: '''
                        export build_number=${BUILD_ID}
                        cp cortx-motr.spec.in cortx-motr.spec
                        sed -i "/BuildRequires.*kernel*/d" cortx-motr.spec
                        sed -i "/BuildRequires.*%{lustre_devel}/d" cortx-motr.spec
                        sed -i 's/@BUILD_DEPEND_LIBFAB@//g' cortx-motr.spec
                        sed -i 's/@.*@/111/g' cortx-motr.spec
                        yum-builddep -y --nogpgcheck cortx-motr.spec
                    ''' 
                }
            }
        }

        stage('Generate Doc') {
            steps {
                script { build_stage = env.STAGE_NAME }
                dir ('motr') {    
                    sh label: '', script: '''
                        rm -rf /root/rpmbuild/RPMS/x86_64/*.rpm
                        ./autogen.sh
                        ./configure --with-user-mode-only
                        export build_number=${BUILD_ID}
                        make doc
                    '''
                }    
            }
        }
   
    }

    post {
        always {
            script{ 
               echo 'Cleanup Workspace.'
                  
                archiveArtifacts artifacts: "motr/doc/**/*.*", onlyIfSuccessful: false, allowEmptyArchive: true
              
                echo 'Cleanup Workspace.'
                deleteDir() /* clean up our workspace */

                env.release_build = (env.release_build != null) ? env.release_build : "" 
                env.release_build_location = (env.release_build_location != null) ? env.release_build_location : ""
                env.component = (env.component).toUpperCase()
                env.build_stage = "${build_stage}"

                env.vm_deployment = (env.deployVMURL != null) ? env.deployVMURL : "" 
                if ( env.deployVMStatus != null && env.deployVMStatus != "SUCCESS" && manager.build.result.toString() == "SUCCESS" ) {
                    manager.buildUnstable()
                }

                def toEmail = ""
                def recipientProvidersClass = [[$class: 'DevelopersRecipientProvider']]
                if( manager.build.result.toString() == "FAILURE" ) {
                    toEmail = "shailesh.vaidya@seagate.com"
                    recipientProvidersClass = [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
                }

                emailext (
                    body: '''${SCRIPT, template="component-email-dev.template"}''',
                    mimeType: 'text/html',
                    subject: "[Jenkins Build ${currentBuild.currentResult}] : ${env.JOB_NAME}",
                    attachLog: true,
                    to: toEmail,
                    //recipientProviders: recipientProvidersClass
                )
            }
        }    
    }
}
