import org.yaml.snakeyaml.Yaml;

def pipeutils, streams, official, repo, gp
def src_config_url, src_config_ref, s3_bucket
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")
    gp = load("gp.groovy")
    repo = "coreos/fedora-coreos-config"


    def pipecfg = pipeutils.load_config()
    src_config_url = pipecfg['source-config-url']
    src_config_ref = pipecfg['source-config-ref']
    s3_bucket = pipecfg['s3-bucket']

    official = pipeutils.isOfficial()
    if (official) {
        echo "Running in official (prod) mode."
    } else {
        echo "Running in unofficial pipeline on ${env.JENKINS_URL}."
    }
}


// Note that the heavy lifting is done on a remote node via gangplank
// so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer

// substitute the right COSA image and mem request into the pod definition before spawning it
pod = pod.replace("COREOS_ASSEMBLER_MEMORY_REQUEST", "${cosa_memory_request_mb}Mi")
pod = pod.replace("COREOS_ASSEMBLER_IMAGE", "coreos-assembler:main")
def podYaml = readYaml(text: pod);

// And re-serialize; I couldn't figure out how to dump to a string
// in a way allowed by the Groovy sandbox.  Tempting to just tell people
// to disable that.
node {
    def tmpPath = "${WORKSPACE}/pod.yaml";
    sh("rm -vf ${tmpPath}")
    writeYaml(file: tmpPath, data: podYaml);
    pod = readFile(file: tmpPath);
    sh("rm -vf ${tmpPath}")
}

echo "Final podspec: ${pod}"

// use a unique label to force Kubernetes to provision a separate pod per run
def pod_label = "cosa-${UUID.randomUUID().toString()}"
podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
    node(pod_label) { container('coreos-assembler') {
        def image = "localhost/coreos-assembler:latest" 
        // print out details of the cosa image to help debugging
        gp.runMultiarchBuild([repo:'quay.io/ravanelli/coreos-assembler-staging', arch: 'aarch64'])
}}}

