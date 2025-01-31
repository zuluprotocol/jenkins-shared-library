/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable CompileStatic */
package io.vegaprotocol

import com.cloudbees.groovy.cps.NonCPS

prefix
portbase
basedir
homedir
homedirRelative
dockerisedvagaScript
validators
nonValidators
dlv

mainnet
genesisFile
checkpointFile
ethEndpointUrl

vegaCoreVersion
dataNodeVersion
vegaWalletVersion

dockerImageVegaCore
dockerImageDataNode
dockerImageVegaWallet

vegatoolsScript

tendermintLogLevel
vegaCoreLogLevel

void init(Map config=[:]) {
    assert config.prefix : 'prefix is required'
    prefix = config.prefix
    assert config.portbase : 'portbase is required'
    portbase = config.portbase
    assert config.basedir : 'basedir is required'
    basedir = config.basedir
    homedir =  "${basedir}/dockerised-${prefix}"
    homedirRelative = "dockerisedvega-home/dockerised-${prefix}"
    dockerisedvagaScript = config.dockerisedvagaScript ?: 'devops-infra/scripts/dockerisedvega.sh'
    assert config.validators : 'validators is required'
    validators = config.validators
    assert config.nonValidators : 'nonValidators is required'
    nonValidators = config.nonValidators

    mainnet = config.mainnet ?: false
    genesisFile = config.genesisFile
    checkpointFile = config.checkpointFile
    ethEndpointUrl = config.ethEndpointUrl

    dlv = config.dlv ?: false

    vegaCoreVersion = config.vegaCoreVersion
    dataNodeVersion = config.dataNodeVersion
    vegaWalletVersion = config.vegaWalletVersion

    dockerImageVegaCore = "ghcr.io/vegaprotocol/vega/vega:${vegaCoreVersion}"
    dockerImageDataNode = "ghcr.io/vegaprotocol/data-node/data-node:${dataNodeVersion}"
    dockerImageVegaWallet = "vegaprotocol/vegawallet:${vegaWalletVersion}"

    assert config.vegatoolsScript : 'vegatoolsScript is required'
    vegatoolsScript = config.vegatoolsScript

    tendermintLogLevel = config.tendermintLogLevel ?: 'info'
    vegaCoreLogLevel = config.vegaCoreLogLevel ?: 'Info'
}

@NonCPS
@Override
String toString() {
    return "DockerisedVega(prefix: \"${prefix}\", portbase: ${portbase}, " +
        "basedir: \"${basedir}\", dockerisedvagaScript: \"${dockerisedvagaScript}\", " +
        "validators: ${validators}, nonValidators: ${nonValidators}, " +
        "mainnet: \"${mainnet}\", genesisFile: \"${genesisFile}\", checkpointFile=\"${checkpointFile}\", " +
        "ethEndpointUrl: \"${ethEndpointUrl}\", " +
        "dlv: ${dlv}, vegaCoreVersion: \"${vegaCoreVersion}\", dataNodeVersion: \"${dataNodeVersion}\", " +
        "vegaWalletVersion: \"${vegaWalletVersion}\", " +
        "dockerImageVegaCore=\"${dockerImageVegaCore}\", dockerImageDataNode=\"${dockerImageDataNode}\", " +
        "dockerImageVegaWallet=\"${dockerImageVegaWallet}\", " +
        "vegatoolsScript=\"${vegatoolsScript}\"" +
        "tendermintLogLevel=\"${tendermintLogLevel}\", vegaCoreLogLevel=\"${vegaCoreLogLevel}\")"
}

void run(String command, boolean resume = false) {
    String extraArguments = ''
    if (vegaCoreVersion) {
        extraArguments += " --vega-version \"${vegaCoreVersion}\""
    }
    if (dataNodeVersion) {
        extraArguments += " --datanode-version \"${dataNodeVersion}\""
    }
    if (vegaWalletVersion) {
        extraArguments += " --vegawallet-version \"${vegaWalletVersion}\""
    }
    if (mainnet) {
        extraArguments += ' --mainnet'
    }
    if (genesisFile) {
        extraArguments += " --genesis \"${genesisFile}\""
    }
    if (checkpointFile) {
        extraArguments += " --resume-checkpoint \"${checkpointFile}\""
    }
    if (ethEndpointUrl) {
        extraArguments += " --eth-endpoint \"${ethEndpointUrl}\""
    }
    if (dlv) {
        extraArguments += ' --dlv'
    }
    if (resume) {
        extraArguments += ' --resume'
    }
    sh label: 'start dockerised-vega', script: """#!/bin/bash -e
        "${dockerisedvagaScript}" \
            --datadir "${basedir}" \
            --prefix "${prefix}" \
            --portbase "${portbase}" \
            --validators "${validators}" \
            --nonvalidators "${nonValidators}" \
            --tendermint-loglevel "${tendermintLogLevel}" \
            --vega-loglevel "${vegaCoreLogLevel}" \
            ${extraArguments} \
            ${command}
    """
}

void start(Map config) {
    boolean resume = config?.resume ?: false
    run('start', resume)
}

void restart(Map config) {
    boolean resume = config?.resume ?: false
    run('restart', resume)
}

void stop(Map config) {
    String extraArguments = ''
    if (config?.resume) {
        extraArguments += ' --resume'
    }
    sh label: 'stop dockerised-vega', script: """#!/bin/bash -e
        "${dockerisedvagaScript}" \
            --prefix '${prefix}' \
            --portbase '${portbase}' \
            ${extraArguments} \
            stop
    """
}

void pull() {
    sh label: 'docker pull for dockerised-vega', script: """#!/bin/bash -e
        "${dockerisedvagaScript}" pull
    """
}

void bootstrapWait() {
    sh label: 'wait for bootstrap to finish', script: """#!/bin/bash -e
        "${dockerisedvagaScript}" \
            --datadir "${basedir}" \
            --prefix '${prefix}' \
            --portbase '${portbase}' \
            bootstrap-wait
    """
}

void printAllContainers() {
    sh label: 'list all the containers', script: """#!/bin/bash -e
        docker ps -a --filter "name=${prefix}"
    """
}

void printAllLogs() {
    for (String containerName : getDockerContainerNames()) {
        String longName = containerName
        String shortName = longName - "${prefix}-"
        sh label: "Logs from ${shortName}",
            script: """#!/bin/bash -e
            echo "==== DOCKER INSPECT '${longName}' ===="
            docker inspect "${longName}"
            echo "==== DOCKER LOGS '${longName}' ===="
            docker logs -t --details ${longName}
            echo "==== DOCKER END '${longName}' ====" ; \
            """
    }
}

void printAllCheckpoints(int node=0) {
    String nodeDataDir = "${basedir}/dockerised-${prefix}/vega/node${node}"
    String checkpointDir = "${nodeDataDir}/.local/state/vega/node/checkpoints"
    sh label: 'list all the checkpoints', script: """#!/bin/bash -e
        find \"${checkpointDir}\" -name '20*.cp'|sort
    """
}

List<String> getDockerContainerNames() {
    return sh(
            script: "docker ps -a --filter 'name=${prefix}' --format '{{.Names}}'",
            returnStdout: true,
        ).trim().split('\n')
}

List<String> getRunningContainerNames() {
    return sh(
            script: "docker ps -a --filter 'name=${prefix}' --filter 'status=running' --format '{{.Names}}'",
            returnStdout: true,
        ).trim().split('\n')
}

boolean isNodeRunning(int node=0) {
    for (String containerName in getRunningContainerNames()) {
        if (containerName.endsWith("vega-node${node}")) {
            return true
        }
    }
    return false
}

String getLatestCheckpointFilepath(int node=0) {
    String nodeDataDir = "${basedir}/dockerised-${prefix}/vega/node${node}"
    String checkpointDir = "${nodeDataDir}/.local/state/vega/node/checkpoints"
    return sh(
            script: "find \"${checkpointDir}\" -name '20*.cp'|sort|tail -1",
            returnStdout: true,
        ).trim()
}

String waitForNextCheckpoint(int node=0) {
    printAllContainers()
    printAllCheckpoints()
    // TODO: improve, cos now there is too many Jenkins Steps created
    String secondLastCheckpointFile = getLatestCheckpointFilepath(node)
    echo "Current last checkpoint: ${secondLastCheckpointFile}"
    while (true) {
        sleep(time:10, unit:'SECONDS')
        String currentLastCheckpointFile = getLatestCheckpointFilepath(node)
        if (secondLastCheckpointFile != currentLastCheckpointFile) {
            echo "Found a new checkpoint: ${currentLastCheckpointFile}"
            return currentLastCheckpointFile
        }
    }
}

void saveResumeCheckpointToFile(String targetFile) {
    String resumeCheckpointFile = checkpointFile
    if (mainnet) {
        resumeCheckpointFile = "${homedir}/updated_mainnet_checkpoint.cp"
    }
    assert resumeCheckpointFile : 'Cannot save resume checkpoint: no checkpointFile specified at startup'
    sh label: 'Convert checkpoint to json format', script: """
        mkdir -p "\$(dirname ${targetFile})"
        "${vegatoolsScript}" checkpoint -v \
            -f "${resumeCheckpointFile}" \
            -o "${targetFile}"
    """
}

void saveLatestCheckpointToFile(String targetFile, int node=0) {
    String latestCheckpointFile = getLatestCheckpointFilepath(node)
    sh label: 'Convert checkpoint to json format', script: """
        mkdir -p "\$(dirname ${targetFile})"
        "${vegatoolsScript}" checkpoint -v \
            -f "${latestCheckpointFile}" \
            -o "${targetFile}"
    """
}

void saveGenesisToFile(String targetFile, int node=0) {
    String generatedGenesisFile = "${basedir}/dockerised-${prefix}/tendermint/node${node}/config/genesis.json"
    sh label: "Copy genesis to ${targetFile}", script: """
        mkdir -p "\$(dirname ${targetFile})"
        cp "${generatedGenesisFile}" "${targetFile}"
    """
}

Map<String,Map<String,String>> getEndpointInformation(String ip) {
    Map<String,Map<String,String>> result = [:]

    for (int node = 0; node < validators; node++) {
        result["validator vega core ${node}"] = [
            'gRPC': "${ip}:${portbase + 10 * node + 2}",
            'gql': "http://${ip}:${portbase + 10 * node + 3}",
            'REST': "http://${ip}:${portbase + 10 * node + 4}"
        ]
    }
    for (int node = validators; node < validators + nonValidators; node++) {
        result["non-validator vega core ${node}"] = [
            'gRPC': "${ip}:${portbase + 10 * node + 2}",
            'gql': "http://${ip}:${portbase + 10 * node + 3}",
            'REST': "http://${ip}:${portbase + 10 * node + 4}"
        ]
    }
    for (int node = validators; node < validators + nonValidators; node++) {
        result["data-node ${node}"] = [
            'gRPC': "${ip}:${portbase + 100 + 10 * node + 7}",
            'gql': "http://${ip}:${portbase + 100 + 10 * node + 8}",
            'REST': "http://${ip}:${portbase + 100 + 10 * node + 9}"
        ]
    }
    for (int node = 0; node < validators + nonValidators; node++) {
        result["tendermint ${node}"] = [
            'REST': "http://${ip}:${portbase + 10 * node + 1}"
        ]
    }
    result['Ganache'] = [
        'REST': "http://${ip}:${portbase + 545}"
    ]
    result['Smart contracts'] = [
        'docs': "http://${ip}:${portbase + 80}"
    ]
    result['Vega faucet'] = [
        'gRPC': "${ip}:${portbase + 790}"
    ]
    result['Vega wallet'] = [
        'gRPC': "${ip}:${portbase + 789}"
    ]

    return result
}

Map<String,String> getUsefulLinks(String ip, int node=0) {
    Map<String,String> result = [:]

    result['Network statistics'] = "http://${ip}:${portbase + 10 * node + 3}/statistics"
    result['Known parties'] = "http://${ip}:${portbase + 10 * node + 3}/parties"
    result['Network parameters'] = "http://${ip}:${portbase + 10 * node + 3}/network/parameters"
    result['Gensis'] = "http://${ip}:${portbase + 10 * node + 1}/genesis"
    result['More'] = 'https://github.com/vegaprotocol/vega/blob/develop/protos/sources/data-node/grpc-rest-bindings.yml'

    return result
}
