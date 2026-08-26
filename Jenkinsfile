// Runs on the Jenkins from `docker compose --profile ci up` (infra/jenkins). Tools are in the image;
// Testcontainers reaches the host Docker daemon through the mounted socket and addresses the
// containers it starts via host.docker.internal. GitHub Actions (.github/workflows/ci.yml) runs the
// same steps.
pipeline {
  agent any
  parameters {
    booleanParam(name: 'PERF', defaultValue: false, description: 'Also run the Testcontainers performance ITs (mvn verify -Dperf)')
    booleanParam(name: 'BUILD_IMAGES', defaultValue: false, description: 'Build the service Docker images after the tests pass')
  }
  options { timestamps(); timeout(time: 60, unit: 'MINUTES'); disableConcurrentBuilds() }
  environment {
    TESTCONTAINERS_HOST_OVERRIDE = 'host.docker.internal'
    MAVEN_OPTS = '-Xmx512m'
  }
  stages {
    stage('Java: build, test, style') {
      steps {
        sh 'mvn -B verify' + (params.PERF ? ' -Dperf' : '')
      }
      post { always { junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml' } }
    }
    stage('assessment-service') {
      steps {
        dir('assessment-service') {
          sh 'python3 -m venv .venv && .venv/bin/pip install -q -r requirements-dev.txt && .venv/bin/python -m pytest -q'
        }
      }
    }
    stage('adjuster-console') {
      steps { dir('adjuster-console') { sh 'npm ci --no-audit --no-fund && npm run typecheck && npm run build' } }
    }
    stage('Helm') {
      steps { sh 'helm lint deploy/helm/claims-platform && helm template claims deploy/helm/claims-platform > /dev/null' }
    }
    stage('Images') {
      when { expression { return params.BUILD_IMAGES } }
      steps {
        script {
          def tag = env.GIT_COMMIT ? env.GIT_COMMIT.take(8) : 'local'
          for (service in ['claim-service', 'payout-service', 'search-service']) {
            sh "docker build -f ${service}/Dockerfile -t claims/${service}:${tag} ."   // multi-module: root context
          }
          for (service in ['assessment-service', 'adjuster-console']) {
            sh "docker build -t claims/${service}:${tag} ./${service}"
          }
        }
      }
    }
  }
}
