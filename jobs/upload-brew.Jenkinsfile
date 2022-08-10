import org.yaml.snakeyaml.Yaml;

def pipeutils, streams, uploading, jenkins_agent_image_tag
def src_config_url, src_config_ref, s3_bucket
node {
    checkout scm
    pipeutils = load("utils.groovy")
    streams = load("streams.groovy")
    pod = readFile(file: "manifests/pod.yaml")


    def pipecfg = pipeutils.load_config()
    s3_bucket = pipecfg['s3-bucket']
    brew_principle = pipecfg['brew-principle'] 
    brew_profile = pipecfg['brew-profile']
    jenkins_agent_image_tag = pipecfg['jenkins-agent-image-tag']
}

arches = [ 'aarch64', 'ppc64le', 'x86_64', 's390x']

properties([
    pipelineTriggers([]),
    parameters([
      choice(name: 'ARCH',
             description: 'The target architecture',
             choices: (arches + 'all'),
             trim: true),
      string(name: 'VERSION',
             description: 'Build version',
             defaultValue: '',
             trim: true),
      string(name: 'RELEASE',
             description: 'Release version',
             defaultValue: '4.10',
             trim: true),
    ]),
    buildDiscarder(logRotator(
        numToKeepStr: '100',
        artifactNumToKeepStr: '100'
    )),
    durabilityHint('PERFORMANCE_OPTIMIZED')
])

// Note that the heavy lifting is done on a remote node via podman
// --remote so we shouldn't need much memory.
def cosa_memory_request_mb = 2.5 * 1024 as Integer


// For the brew upload, we just need to download from s3 the metadata files
// and upload it to brew, we do not a lot of resources
pod = pod.replace("COREOS_ASSEMBLER_CPU_REQUEST", "1")
pod = pod.replace("COREOS_ASSEMBLER_CPU_LIMIT", "1")

// substitute the right COSA image and mem request into the pod definition before spawning it
pod = pod.replace("COREOS_ASSEMBLER_MEMORY_REQUEST", "${cosa_memory_request_mb}Mi")
pod = pod.replace("JENKINS_AGENT_IMAGE_TAG", "latest")

def podYaml = readYaml(text: pod);

node {
    def tmpPath = "${WORKSPACE}/pod.yaml";
    sh("rm -vf ${tmpPath}")
    writeYaml(file: tmpPath, data: podYaml);
    pod = readFile(file: tmpPath);
    sh("rm -vf ${tmpPath}")
}

echo "Final podspec: ${pod}"

// use a unique label to force Kubernetes to provision a separate pod per run
def pod_label = "brew-${UUID.randomUUID().toString()}"


 currentBuild.description = "[${params.VERSION}][${params.ARCH}] Running"

 podTemplate(cloud: 'openshift', label: pod_label, yaml: pod) {
 node(pod_label) { container('coreos-assembler') {

     def basearch = params.ARCH
     def version = params.VERSION
     def release = params.RELEASE

     try { timeout(time: 240, unit: 'MINUTES') {
         if (s3_bucket && brew_principle && brew_profile) {
             withCredentials([aws(credentialsId: 'aws-internal-credential')]) {
                 stage('BuildFetch') {
                     def s3_stream_dir = "${s3_bucket}/releases/rhcos-${release}";
                     // We don't want to build anything
                     // touch the builds-source to allow buildfetch only
                     shwrap("""
                         mkdir tmp
                         touch tmp/builds-source.txt
                         cosa buildfetch --arch=${arch} \
                         --url s3://${s3_stream_dir} \
                         --build ${version}

                     """)
                     shwrap(""" 
                        cp /cosa/coreos-assembler-git.json  builds/${version}/${arch}/coreos-assembler-config-git.json
                     """)
                 }
             }

             stage('Brew Upload') {
                 withCredentials([
                     file(credentialsId: 'brew-cfg', variable: 'BREW'),
                     file(credentialsId: 'krb5', variable: 'KRB5'),
                     file(credentialsId: 'keytab-keytab', variable: 'KEYTAB'),
                     file(credentialsId: 'brew-ca', variable: 'CA')
             ]) {
                     shwrap("""
                         cp $CA /etc/pki/brew
                         cp $BREW /etc/koji.conf
                         cp $KRB5 /etc/krb5.conf
                         kinit -V -f -t $KEYTAB -k rhcos-build/jenkins-redhat-coreos.cloud.paas.upshift.redhat.com@REDHAT.COM
                         klist -A
                         coreos-assembler koji-upload \
                         upload --reserve-id \
                          --log-level debug \
                         --build ${version} \
                         --retry-attempts 6 \
                         --buildroot builds \
                         --owner ${brew_principle} \
                         --profile ${brew_profile} \
                         --tag rhaos-4.12-rhel-8-build \
                         --keytab $KEYTAB
                     """)
                 }
             }
             currentBuild.result = 'SUCCESS'
    }  

         // main timeout and try {} finish here
         }} catch (e) {
             currentBuild.result = 'FAILURE'
             throw e
         } finally {
             def color
             def message = "[${params.VERSION}][${basearch}]"

             if (currentBuild.result == 'SUCCESS') {
                 message = ":fcos: :sparkles: ${message} - SUCCESS"
                 color = 'good';
             } else {
                 message = ":fcos: :trashfire: ${message} - FAILURE"
                 color = 'danger';
             }
         }
     }
   }
}
