/**
 * Build docker tempest image
 * GIT_URL - URL of git repository
 * GIT_REF - Git reference to checkout
 * GIT_CREDS_ID - Credensial to use for repository access
 * DOCKER_REGISTRY - Docker registry to which push built image
 * IMAGE_NAME - Docker image name to buil
 * IMAGE_TAG - Linux distribution to build docker image from
 */

def mkCommon = new com.mirantis.mk.Common()
def artifactory = new com.mirantis.mcp.MCPArtifactory()
def git = new com.mirantis.mcp.Git()

def built_image
def docker_dev_repo = 'docker-dev-local'
def docker_context = '.'

// Guess username for acessing git repo
def git_user=''
if (env.GIT_CREDS_ID) {
    def cred = mkCommon.getCredentials(env.GIT_CREDS_ID, 'key')
    git_user = "${cred.username}@"
}

def artifactoryServer = Artifactory.server('mcp-ci')

// Set current build description
if (env.GERRIT_CHANGE_URL) {
    currentBuild.description = """
    <p>
      Triggered by change: <a href="${env.GERRIT_CHANGE_URL}">${env.GERRIT_CHANGE_NUMBER},${env.GERRIT_PATCHSET_NUMBER}</a><br/>
      Project: <b>${env.GERRIT_PROJECT}</b><br/>
      Branch: <b>${env.GERRIT_BRANCH}</b><br/>
      Subject: <b>${env.GERRIT_CHANGE_SUBJECT}</b><br/>
    </p>
    """
} else {
    currentBuild.description = """
    <p>
      Triggered manually<br/>
      Git repository URL: <b>${GIT_URL}</b><br/>
      Git revision: <b>${GIT_REF}</b><br/>
    </p>
    """
}

node('docker') {

    stage('SCM checkout') {
        def project = 'mcp/' + GIT_URL.split('/')[-1]
        def host = 'gerrit.mcp.mirantis.net'
        git.gitSSHCheckout([
          credentialsId : GIT_CREDS_ID,
          branch : GIT_URL,
          host : host,
          project : project,
          withWipeOut : true,
        ])
    }

    // Build image
    stage('Build ' + IMAGE_NAME.split('/')[-1]) {
        def docker_args = [
            '--pull',
            '--no-cache',
            docker_context,
        ]
        built_image = docker.build(
            ( DOCKER_REGISTRY ? "${DOCKER_REGISTRY}/" : '' ) + "${IMAGE_NAME}:${IMAGE_TAG}",
            docker_args.join(' ')
        )
    }

    // Push image to registry
    if (env.DOCKER_REGISTRY) {
        stage('Push ' + IMAGE_NAME.split('/')[-1]) {
            artifactory.uploadImageToArtifactory(
                artifactoryServer,
                DOCKER_REGISTRY,
                IMAGE_NAME,
                IMAGE_TAG,
                docker_dev_repo
            )
        }
    }
}
