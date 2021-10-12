/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey

void call() {

    properties([
        copyArtifactPermission('*'),
        disableConcurrentBuilds(),
        parameters([
            string(
                name: 'VEGA_CORE_VERSION', defaultValue: pipelineDefaults.dev.vegaCoreVersion,
                description: 'Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core.'),
            booleanParam(
                name: 'DEPLOY_CONFIG', defaultValue: pipelineDefaults.dev.deployConfig,
                description: 'Deploy some Vega Network config, e.g. genesis file'),
            booleanParam(
                name: 'RESTART_NETWORK', defaultValue: pipelineDefaults.dev.restartNetwork,
                description: 'Restart the Network'),
            string(
                name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.dev.devopsInfraBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
        ])
    ])

    echo "params=${params}"

    node {
        SSHUserPrivateKey sshDevnetCredentials = sshUserPrivateKey( credentialsId: 'ssh-vega-network',
                                                                    keyFileVariable: 'PSSH_KEYFILE',
                                                                    usernameVariable: 'PSSH_USER')
        Map dockerCredentials = [credentialsId: 'github-vega-ci-bot-artifacts',
                                           url: 'https://docker.pkg.github.com']
        skipDefaultCheckout()
        cleanWs()

        timestamps {
            try {
                timeout(time: 20, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                    }
                    stage('Git Clone') {
                        parallel([
                            'devops-infra': {
                                dir('devops-infra') {
                                    gitClone('devops-infra', params.DEVOPS_INFRA_BRANCH)
                                }
                            },
                            'vega core': {
                                if (params.VEGA_CORE_VERSION) {
                                    dir('vega') {
                                        gitClone('vega', params.VEGA_CORE_VERSION)
                                    }
                                } else {
                                    echo 'Skip: VEGA_CORE_VERSION not specified'
                                    Utils.markStageSkippedForConditional('vega core')
                                }
                            }
                        ])
                    }
                    String buildStageName = 'Build Vega Core binary'
                    stage(buildStageName) {
                        if (params.VEGA_CORE_VERSION) {
                            dir('vega') {
                                String hash = sh(
                                    script: 'git rev-parse HEAD|cut -b1-8',
                                    returnStdout: true,
                                ).trim()
                                String ldflags = "-X main.CLIVersion=develop -X main.CLIVersionHash=${hash}"
                                sh label: 'Compile vega core', script: """
                                    go build -v -o ./cmd/vega/vega-linux-amd64 -ldflags "${ldflags}" ./cmd/vega
                                """
                                sh label: 'Sanity check', script: '''
                                    file ./cmd/vega/vega-linux-amd64
                                    ./cmd/vega/vega-linux-amd64 version
                                '''
                            }
                        } else {
                            echo 'Skip: VEGA_CORE_VERSION not specified'
                            Utils.markStageSkippedForConditional(buildStageName)
                        }
                    }
                    stage('Devnet: status') {
                        dir('vega') {
                            withDockerRegistry(dockerCredentials) {
                                withCredentials([sshDevnetCredentials]) {
                                    sh script: './veganet.sh devnet status'
                                }
                            }
                        }
                    }
                    String deployStageName = 'Deploy Vega Core binary'
                    stage(deployStageName) {
                        if (params.VEGA_CORE_VERSION) {
                            withEnv([
                                "VEGA_CORE_BINARY=${env.WORKSPACE}/vega/cmd/vega/vega-linux-amd64",
                            ]) {
                                dir('vega') {
                                    withDockerRegistry(dockerCredentials) {
                                        withCredentials([sshDevnetCredentials]) {
                                            sh script: './veganet.sh devnet pushvega'
                                        }
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: VEGA_CORE_VERSION not specified'
                            Utils.markStageSkippedForConditional(deployStageName)
                        }
                    }
                    String deployConfigStageName = 'Deploy Vega Network Config'
                    stage(deployConfigStageName) {
                        if (params.DEPLOY_CONFIG) {
                            dir('vega/ansible') {
                                withCredentials([sshDevnetCredentials]) {
                                    // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                    //        are set by withCredentials wrapper
                                    sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                        export ANSIBLE_FORCE_COLOR=true
                                        ansible-playbook \
                                            -u "\${PSSH_USER}" \
                                            --private-key "\${PSSH_KEYFILE}" \
                                            -i hosts \
                                            --limit devnet \
                                            --tags vega-network-config \
                                            site.yaml
                                    """
                                }
                            }
                        } else {
                            echo 'Skip: DEPLOY_CONFIG is false'
                            Utils.markStageSkippedForConditional(deployConfigStageName)
                        }
                    }
                    String restartStageName = 'Restart Network'
                    stage(restartStageName) {
                        if (params.RESTART_NETWORK) {
                            dir('vega') {
                                withDockerRegistry(dockerCredentials) {
                                    withCredentials([sshStagnetCredentials]) {
                                        sh script: './veganet.sh devnet bounce'
                                    }
                                }
                            }
                        } else {
                            echo 'Skip: RESTART_NETWORK is false'
                            Utils.markStageSkippedForConditional(restartStageName)
                        }
                    }
                }
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = currentBuild.result ?: 'SUCCESS'
                // result can be SUCCESS or UNSTABLE
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                // Workaround Jenkins problem: https://issues.jenkins.io/browse/JENKINS-47403
                // i.e. `currentResult` is not set properly in the finally block
                // CloudBees workaround: https://support.cloudbees.com/hc/en-us/articles/218554077-how-to-set-current-build-result-in-pipeline
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Cleanup') {
                    slack.slackSendDeployStatus network: 'Devnet',
                        version: params.VEGA_CORE_VERSION,
                        restart: params.RESTART_NETWORK
                }
            }
        }
    }
}

void gitClone(String repo, String branch) {
    retry(3) {
        checkout([
            $class: 'GitSCM',
            branches: [[name: branch]],
            userRemoteConfigs: [[
                url: "git@github.com:vegaprotocol/${repo}.git",
                credentialsId: 'vega-ci-bot'
            ]]])
    }
}
