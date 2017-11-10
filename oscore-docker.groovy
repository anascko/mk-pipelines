/**
 * Build docker tempest image
 * Docker image build pipeline
 * IMAGE_NAME - Image name
 * IMAGE_GIT_URL - Image git repo URL
 * IMAGE_BRANCH - Image repo branch
 * IMAGE_CREDENTIALS_ID - Image repo credentials id
 * IMAGE_TAGS - Image tags
 * DOCKERFILE_PATH - Relative path to docker file in image repo
 * REGISTRY_URL - Docker registry URL (can be empty)
 * REGISTRY_CREDENTIALS_ID - Docker hub credentials id
 */

def mkCommon = new com.mirantis.mk.Common()
artifactory = new com.mirantis.mcp.MCPArtifactory()

def gerrit = new com.mirantis.mk.Gerrit()
def git = new com.mirantis.mk.Git()
def dockerLib = new com.mirantis.mk.Docker()

def built_image
def docker_dev_repo = 'docker-dev-local'

def git_repo_url = "${env.GIT_URL}"
def git_ref = "${env.GIT_REF}"
def git_credentials_id = "${env.GIT_CREDS_ID}"

def docker_registry = "${env.DOCKER_REGISTRY}"
def docker_context = '.'
def docker_image_name = "${env.IMAGE_NAME}".split('/')[-1]   #local_image_name
def docker_image_tag = "${env.IMAGE_TAG}"

// Guess username for acessing git repo
def git_user=''
if (env.GERRIT_USER) {
    git_user = "${env.GERRIT_USER}@"
} else if (env.GIT_CREDS_ID) {
    def cred = mkCommon.getCredentials(env.GIT_CREDS_ID, 'key')
    git_user = "${cred.username}@"
}

artifactoryServer = Artifactory.server('mcp-ci')

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
      Git repository URL: <b>${git_repo_url}</b><br/>
      Git revision: <b>${git_ref}</b><br/>
    </p>
    """
}

node('docker') {

    stage('SCM checkout') {
        echo "Checking out git repository from ${git_repo_url} @ ${git_ref}"
        checkout([
            $class: 'GitSCM',
            branches: [
                [name: 'FETCH_HEAD'],
            ],
            userRemoteConfigs: [
                [url: git_repo_url, refspec: git_ref, credentialsId: git_credentials_id],
            ],
        ])
    }

    // Build image
    stage('Build ' + docker_image_name.split('/')[-1]) {
        def docker_args = [
            '--pull',
            '--no-cache',
            docker_context,
        ]
        built_image = docker.build(
            ( docker_registry ? "${docker_registry}/" : '' ) + "${docker_image_name}:${docker_image_tag}",
            docker_args.join(' ')
        )
    }

    // Push image to registry
    if (docker_registry) {
        stage('Push ' + docker_image_name.split('/')[-1]) {
            artifactory.uploadImageToArtifactory(
                artifactoryServer,
                docker_registry,
                docker_image_name,
                docker_image_tag,
                docker_dev_repo
            )
        }
    }
}
