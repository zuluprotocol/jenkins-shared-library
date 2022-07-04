// https://github.com/janinko/ghprb/issues/77
def scmDefinition(args){
  return {
    cpsScm {
      scm {
        git {
          if (args.branch) {
              branch("*/${args.branch}")
          }
          remote {
            url(args.repo)
            credentials(args.get('credentials', "vega-ci-bot"))
            if (args.branch) {
                refspec("+refs/heads/${args.branch}:refs/remotes/origin/${args.branch}")
            }
          }
        }
      }
      scriptPath(args.get('jenkinsfile', 'Jenkinsfile'))
    }
  }
}


def h(def text, def num=4) {
    return "<h${num}>${text}</h${num}>"
}


def standardDescription() {
    def url = "https://github.com/vegaprotocol/jenkins-shared-library/tree/main/dsl"
    return h("""
        This job was automatically generated by DSL script located at <a href="${url}">this repository</a> and processed by <a href='${binding.variables.get('JOB_URL')}'>this job</a>, any manual configuration will be overriden.
    """, 5)
}


def createCommonPipeline(args){
    args.repo = "git@github.com:vegaprotocol/${args.repo}.git"
    return pipelineJob(args.name) {
        def des = args.get('description', '')
        des += "${des ? '<br/>' : ''} ${standardDescription()}"
        description(des)
        logRotator {
            daysToKeep(45)
        }
        if (args.parameters) {
            parameters args.parameters
        }
        environmentVariables {
            keepBuildVariables(true)
            keepSystemVariables(true)
            args.env.each { key, value ->
                env(key.toUpperCase(), value)
            }
        }
        if (args.get('useScmDefinition', true)) {
            definition scmDefinition(args)
            properties {
                pipelineTriggers {
                    triggers {
                        githubPush()
                    }
                }
            }
        }
        else {
            definition args.definition
        }

    }
}


def jobs = [
    // Capsule playground
    [
        name: 'private/Deployments/Stagnet 3',
        useScmDefinition: false,
        parameters: {
            booleanParam('BUILD_CAPSULE', true, h('decide if build vegacapsule from source if false VEGACAPSULE_VERSION will be looked up in releases page', 5))
            stringParam('VEGACAPSULE_VERSION', 'ops-development', h('version of vegacapsule (tag, branch, any revision)'))
            stringParam('VEGA_VERSION', 'v0.52.0', h('version of vega core (tag)'))
            stringParam('DATA_NODE_VERSION', 'v0.52.0', h('version of data node (tag)'))
            choiceParam('ACTION', ['RESTART', 'START', 'STOP'], h('action to be performed with network'))
            booleanParam('REGENERATE_CONFIGS', false, h('check this to regenerate network configs with capsule', 5))
            booleanParam('UNSAFE_RESET_ALL', false, h('decide if vegacapsule should perform unsafe-reset-all on RESTART action', 5))
        },
        definition: {
            cps {
                script("""
                @Library('vega-shared-library@main') _
                capsulePipelineWrapper()
                """)
            }
        },
        env: [
            S3_CONFIG_HOME: "s3://vegacapsule-test/stagnet3",
            NOMAD_ADDR: "https://n00.stagnet3.vega.xyz:4646",
        ],
    ],
    // DSL Job - the one that manages this file
    [
        name: 'private/DSL Job',
        repo: 'jenkins-shared-library',
        description: h('this job is used to generate other jobs'),
        jenkinsfile: 'dsl/Jenkinsfile',
        branch: 'main',
    ],
    // Jenkins Configuration As Code
    [
        name: 'private/Jenkins Configuration as Code Pipeline',
        repo: 'jenkins-shared-library',
        description: h('This job is used to auto apply changes to jenkins instance configuration'),
        jenkinsfile: 'jcasc/Jenkinsfile',
        branch: 'main',
    ],
    [
        name: 'private/Deployments/Veganet/Devnet',
        useScmDefinition: false,
        definition: {
            cps {
                script("""
                @Library('vega-shared-library@main') _
                pipelineDeploy()
                """)
            }
        },
        env: [
            NET_NAME: 'devnet',
        ],
        parameters: {
            stringParam('VEGA_CORE_VERSION', 'develop', "Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core. If you decide not to build binary by yourself you need to set version according to the versions available on releases page: https://github.com/vegaprotocol/vega/releases")
            booleanParam('DEPLOY_CONFIG', true, 'Deploy some Vega Network config, e.g. genesis file')
            booleanParam('BUILD_VEGA_CORE', true, 'Decide if VEGA_CORE_VERSION is to be build or downloaded')
            choiceParam('RESTART', ['YES', 'NO'], 'Restart the Network') // do not support checkpoints for devnet
            // choiceParam('RESTART', ['YES', 'YES_FROM_CHECKPOINT', 'NO'], 'Restart the Network')
            booleanParam('CREATE_MARKETS', true, 'Create markets')
            booleanParam('CREATE_INCENTIVE_MARKETS', true, 'Create Markets for Incentive')
            booleanParam('BOUNCE_BOTS', true, 'Start & Top up liqbot and traderbot with fake/ERC20 tokens')
            booleanParam('REMOVE_WALLETS', false, 'Remove bot wallets on top up')
            stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
            stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
            stringParam('ANSIBLE_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/ansible repository')
        }
    ]
]

// MAIN
jobs.each { job ->
    createCommonPipeline(job)
}