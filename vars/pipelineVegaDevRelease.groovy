void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    Map dockerCredentials = [
        credentialsId: 'github-vega-ci-bot-artifacts',
        url: 'https://ghcr.io'
    ]
    def githubAPICredentials = usernamePassword(
        credentialsId: 'github-vega-ci-bot-artifacts',
        passwordVariable: 'GITHUB_API_TOKEN',
        usernameVariable: 'GITHUB_API_USER'
    )

    def doGitClone = { repo, branch ->
        dir(repo) {
            retry(3) {
                // returns object:
                // [GIT_BRANCH:origin/master,
                // GIT_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_SUCCESSFUL_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41, 
                // GIT_URL:git@github.com:vegaprotocol/devops-infra.git]
                return checkout([
                    $class: 'GitSCM',
                    branches: [[name: branch]],
                    userRemoteConfigs: [[
                        url: "git@github.com:vegaprotocol/${repo}.git",
                        credentialsId: 'vega-ci-bot'
                    ]]])
            }
        }
    }

    def versionTag = 'UNKNOWN'
    def protocolUpgradeBlock = -1

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 40, unit: 'MINUTES')
            timestamps()
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                }
            }
            //
            // Begin Git CLONE
            //
            stage('Checkout') {
                parallel {
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                doGitClone('vega', params.VEGA_VERSION)
                            }
                            // add commit hash to version
                            dir('vega') {
                                script {
                                    def versionHash = sh(
                                        script: "git rev-parse --short HEAD",
                                        returnStdout: true,
                                    ).trim()
                                    def orgVersion = sh(
                                        script: "grep -o '\"v0.*\"' version/version.go",
                                        returnStdout: true,
                                    ).trim()
                                    orgVersion = orgVersion.replace('"', '')
                                    versionTag = orgVersion + '-' + versionHash
                                }
                                sh label: 'Add hash to version', script: """#!/bin/bash -e
                                    sed -i 's/"v0.*"/"${versionTag}"/g' version/version.go
                                """
                                print('Binary version ' + versionTag)
                            }
                        }
                    }
                }
            }
            //
            // End Git CLONE
            //
            //
            // Begin COMPILE
            //
            stage('Compile') {
                matrix {
                    axes {
                        axis {
                            name 'GOOS'
                            values 'linux', 'darwin'
                        }
                        axis {
                            name 'GOARCH'
                            values 'amd64', 'arm64'
                        }
                    }
                    stages {
                        stage('Build') {
                            environment {
                                GOOS         = "${GOOS}"
                                GOARCH       = "${GOARCH}"
                            }
                            options { retry(3) }
                            steps {
                                sh 'printenv'
                                dir('vega') {
                                    sh label: 'Compile', script: """#!/bin/bash -e
                                        go build -v \
                                            -o ../build-${GOOS}-${GOARCH}/ \
                                            ./cmd/vega \
                                            ./cmd/data-node \
                                            ./cmd/visor
                                    """
                                    sh label: 'check for modifications', script: 'git diff'
                                }
                                dir("build-${GOOS}-${GOARCH}") {
                                    sh label: 'list files', script: '''#!/bin/bash -e
                                        pwd
                                        ls -lah
                                    '''
                                    sh label: 'Sanity check', script: '''#!/bin/bash -e
                                        file *
                                    '''
                                    script {
                                        if ( GOOS == "linux" && GOARCH == "amd64" ) {
                                            sh label: 'get version', script: '''#!/bin/bash -e
                                                ./vega version
                                                ./data-node version
                                                ./visor version
                                            '''
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            //
            // End COMPILE
            //
            //
            // Begin ZIP
            //
            stage('zip') {
                steps {
                    sh label: 'zip binaries', script: """#!/bin/bash -e
                        rm -rf ./release
                        mkdir -p ./release
                        # linux amd64
                        zip ./release/vega-linux-amd64.zip ./build-linux-amd64/vega
                        zip ./release/data-node-linux-amd64.zip ./build-linux-amd64/data-node
                        zip ./release/visor-linux-amd64.zip ./build-linux-amd64/visor
                        # linux arm64
                        zip ./release/vega-linux-arm64.zip ./build-linux-arm64/vega
                        zip ./release/data-node-linux-arm64.zip ./build-linux-arm64/data-node
                        zip ./release/visor-linux-arm64.zip ./build-linux-arm64/visor

                        # MacOS amd64
                        zip ./release/vega-darwin-amd64.zip ./build-darwin-amd64/vega
                        zip ./release/data-node-darwin-amd64.zip ./build-darwin-amd64/data-node
                        zip ./release/visor-darwin-amd64.zip ./build-darwin-amd64/visor
                        # MacOS arm64
                        zip ./release/vega-darwin-arm64.zip ./build-darwin-arm64/vega
                        zip ./release/data-node-darwin-arm64.zip ./build-darwin-arm64/data-node
                        zip ./release/visor-darwin-arm64.zip ./build-darwin-arm64/visor
                    """
                }
            }
            //
            // End ZIP
            //
            //
            // Begin PUBLISH
            //
            stage('Publish to GitHub vega-dev-releases') {
                environment {
                    TAG_NAME = "${versionTag}"
                }
                steps {
                    script {
                        withGHCLI('credentialsId': 'github-vega-ci-bot-artifacts') {
                            sh label: 'Upload artifacts', script: """#!/bin/bash -e
                                gh release view $TAG_NAME --repo vegaprotocol/vega-dev-releases \
                                && gh release upload $TAG_NAME ../release/* --repo vegaprotocol/vega-dev-releases \
                                || gh release create $TAG_NAME ./release/* --repo vegaprotocol/vega-dev-releases
                            """
                        }
                    }
                }
            }
            //
            // End PUBLISH
            //
        }
        post {
            always {
                cleanWs()
            }
        }
    }
}
