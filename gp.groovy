////Run podman remote build and push
// Available parameters:
//    arch:              string -- Build architecture
//    tag:               string -- Image tag
//    repo:              string -- Quay repository 
def buildRemote(params = [:]) {
    arch = params['arch']
    repo = params['repo']
    tag = params['tag'] 

    withCredentials([
        string(credentialsId: "fcos-${arch}-builder-host-string",
               variable: 'REMOTEHOST'),
        string(credentialsId: "fcos-${arch}-builder-uid-string",
               variable: 'REMOTEUID'),
        sshUserPrivateKey(credentialsId: "fcos-${arch}-builder-sshkey-key",
                          usernameVariable: 'REMOTEUSER',
                          keyFileVariable: 'CONTAINER_SSHKEY')
    ]) {
        withEnv(["CONTAINER_HOST=ssh://core@44.204.26.146/run/user/1000/podman/podman.sock"]) {
            shwrap("""
            # workaround bug: https://github.com/jenkinsci/configuration-as-code-plugin/issues/1646
            sed -i s/^----BEGIN/-----BEGIN/ \$CONTAINER_SSHKEY
            """)
            shwrap("""
               podman rmi -a -f
               podman --remote build --tag $repo:$tag -f src/Dockerfile
               podman login -u="ravanelli" -p="jRuEmlcQ5PjoOywRlQ8NdUvOU4rMVifwYbpaIgfxUhxL7Gv69gYOA0kidtf7xW5lAkllDjXlcp66WhZ23iZydQ==" quay.io
               podman --remote push $repo:$tag > /tmp/tags.txt
            """)
        }
    }
}
// Create and push a manifest list
// Available parameters:
//    tag:               string -- Image tag
//    repo:              string -- Quay repository
def createManifest(params = [:]) {
    repo = params['repo']
    tag = params['tag'] 

    shwrap("""
        podman manifest create $repo:main
        podman manifest add --features cosa_commit=$tag $repo:main docker://$repo:$tag
        podman manifest push --all $repo:main docker://$repo:main  --remove-signatures -f v2s2
    """)
}
// Remove all local images and containers:
def cleanImages(params = [:]) {
    shwrap("""
        podman rmi -a -f
    """)
} 
// Clone a git repository in the following dir:
//    dir:               string -- Directory
//    git_ref:           string -- Git branch
//    git_uri:           string -- Git URL
def gitClone(params = [:]) {
    def path = params.get('dir', "./src");
    def branch = params.get('git_uri', "main");
    def repo = params.get('git_ref', "https://github.com/ravanelli/coreos-assembler.git");
    dir(path) {
        git branch: branch, url: repo
    } 
}
// Run a build using podman remote and create
// a manifest using it:
//    dir:               string -- Directory
//    git_ref:           string -- Git branch
//    git_uri:           string -- Git URL
def runMultiarchBuild(params = [:]) {
    def path = params.get('dir', "./src");
    gitClone(params)
    def sha = shwrapCapture("""cd $path; git rev-parse --short HEAD""")
    def tag = "${params['arch']}-2h-$sha"
    params['tag'] = tag
    //buildRemote(params)    

    shwrap("""sleep 11000""")
    shwrapCapture(""" podman login -u="ravanelli" -p="jRuEmlcQ5PjoOywRlQ8NdUvOU4rMVifwYbpaIgfxUhxL7Gv69gYOA0kidtf7xW5lAkllDjXlcp66WhZ23iZydQ==" quay.io """)
    shwrapCapture(""" podman search --list-tags quay.io/ravanelli/coreos-assembler-staging > ./tags.txt """)
    
    def tags = shwrapCapture("""cat ./tags.txt """)
    if (tags.contains(tag)) {
        echo("Build and Push to quay done successfully via tag:$tag")
    }
    else {
       echo("Quay doesn't seem to have tag:$tag")
    }
} 
return this
