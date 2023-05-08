node {
    checkout scm
    // these are script global vars
    pipeutils = load("utils.groovy")
    pipecfg = pipeutils.load_pipecfg()
    libcloud = load("libcloud.groovy")

}
def brew_principal = "rhcos-build/jenkins-rhcos.apps.ocp-virt.prod.psi.redhat.com@IPA.REDHAT.COM"
brew_profile = "brew"

properties([
    pipelineTriggers([]),
    parameters([
      string(name: 'ADDITIONAL_ARCHES',
             description: "Override additional architectures (space-separated). " +
                          "Use 'none' to only release for x86_64. " +
                          "Supported: ${pipeutils.get_supported_additional_arches().join(' ')}",
             defaultValue: "",
             trim: true),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      choice(name: 'STREAM',
             choices: pipeutils.get_streams_choices(pipecfg),
             description: 'CoreOS stream to upload to brew'),
      booleanParam(name: 'ALLOW_MISSING_ARCHES',
                   defaultValue: false,
                   description: 'Allow release to continue even with missing architectures'),
      // Default to true for CLOUD_REPLICATION because the only case
      // where we are running the job by hand is when we're doing a
      // production release and we want to replicate there. Defaulting
      // to true means there is less opportunity for human error
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

def build_description = "[${params.VERSION}]"

// runtime parameter always wins
def cosa_img = params.COREOS_ASSEMBLER_IMAGE
cosa_img = "quay.io/ravanelli/coreos-assembler:brew-new"
def basearches = []
if (params.ADDITIONAL_ARCHES != "none") {
    basearches = params.ADDITIONAL_ARCHES.split() as List
    basearches = basearches ?: pipeutils.get_additional_arches(pipecfg, params.STREAM)
}

// We don't need that much memory for downloading/uploading to brew, since
// it will be mostly metadata
def cosa_memory_request_mb = 1 * 1024 as Integer

// Same here, we don't need that much
def ncpus = 1

echo "Waiting for upload-brew lock"
currentBuild.description = "${build_description} Waiting"
def stream_info = pipecfg.streams[params.STREAM]

lock(resource: "upload-brew") {
    cosaPod(cpu: "${ncpus}",
            memory: "${cosa_memory_request_mb}Mi",
            image: cosa_img,
            serviceAccount: "jenkins",
            secrets: ["brew-keytab", "brew-ca:ca.crt:/etc/pki/ca.crt",
                      "koji-conf:koji.conf:/etc/koji.conf",
                      "krb5-conf:krb5.conf:/etc/krb5.conf"]) {
    timeout(time: 240, unit: 'MINUTES') {
    try {
        stage('Fetch Metadata') {
            def ref = pipeutils.get_source_config_ref_for_stream(pipecfg, params.STREAM)
            def variant = stream_info.variant ? "--variant ${stream_info.variant}" : ""
            def s3_stream_dir = pipeutils.get_s3_streams_dir(pipecfg, params.STREAM)
            pipeutils.shwrapWithAWSBuildUploadCredentials("""
                cosa init --branch ${ref} ${variant} ${pipecfg.source_config.url}
                cosa buildfetch --build=${params.VERSION} \
                    --arch=all --url=s3://${s3_stream_dir}/builds \
                    --aws-config-file \${AWS_BUILD_UPLOAD_CONFIG} \
                    --file "coreos-assembler-config-git.json"
            """)
        }
        
        def builtarches = shwrapCapture("""
                          cosa shell -- cat builds/builds.json | \
                              jq -r '.builds | map(select(.id == \"${params.VERSION}\"))[].arches[]'
                          """).split() as Set
        assert builtarches.contains("x86_64"): "The x86_64 architecture was not in builtarches."
        if (!builtarches.containsAll(basearches)) {
            if (params.ALLOW_MISSING_ARCHES) {
                warn("Some requested architectures did not successfully build!")
                basearches = builtarches.intersect(basearches)
            } else {
                echo "ERROR: Some requested architectures did not successfully build"
                echo "ERROR: Detected built architectures: $builtarches"
                echo "ERROR: Requested base architectures: $basearches"
                currentBuild.result = 'FAILURE'
                return
            }
        }
        if (brew_profile) {
            stage('Brew Upload') {
                def tag = pipecfg.streams[params.STREAM].brew_tag
                for (arch in basearches) {
                    def state = false
                    def version = (params.VERSION).replaceAll("-", ".")
                    def nvr = "rhcos-${arch}-${version}-0"
                    echo("${nvr}")
                    state = shwrapCapture("""
                    coreos-assembler koji-upload search \
                        --nvr ${nvr} \
                        --build ${params.VERSION} \
                        --keytab "/run/kubernetes/secrets/brew-keytab/brew.keytab" \
                        --owner ${brew_principal} \
                        --profile ${brew_profile}
                    """)
                    echo("${state}")
                    // Check if no Brew upload was done yet
                    // State 1 means brew build complete
                    if (state != "1") {
                        shwrap("""
                            coreos-assembler koji-upload \
                                upload --reserve-id \
                                --keytab "/run/kubernetes/secrets/brew-keytab/brew.keytab" \
                                --build ${params.VERSION} \
                                --retry-attempts 6 \
                                --buildroot builds \
                                --owner ${brew_principal} \
                                --profile ${brew_profile} \
                                --tag ${tag} \
                                --arch ${arch}
                         """)
                    }
                }
            }
        }
        currentBuild.result = 'SUCCESS'

    } catch (e) {
        currentBuild.result = 'FAILURE'
        throw e
    } finally {
        def message = "[${params.STREAM}][${params.VERSION}][${params.ARCH}]"
        echo message
        //if (currentBuild.result != 'SUCCESS') {
        //    pipeutils.trySlackSend(message: ":beer: brew-upload <${env.BUILD_URL}|#${env.BUILD_NUMBER}> ${message}")
       // }
    }
}}} // timeout, cosaPod, and lock finish here

