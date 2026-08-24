// Declarative pipeline mirroring .github/workflows/ci.yml. GitHub Actions is the CI that
// actually runs for this repo; this file documents the same pipeline for a Jenkins setup.
pipeline {
  agent any
  options { timestamps(); timeout(time: 40, unit: 'MINUTES') }
  tools { jdk 'temurin-21'; maven 'maven-3.9' }
  environment { TESTCONTAINERS_RYUK_DISABLED = 'false' }

  stages {
    stage('Build & test') {
      parallel {
        stage('Java services') {
          steps { sh 'mvn -B verify' }   // Testcontainers: needs Docker on the agent
          post { always { junit '**/target/surefire-reports/*.xml' } }
        }
        stage('assessment-service') {
          steps { dir('assessment-service') { sh 'python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest -q' } }
        }
        stage('adjuster-console') {
          steps { dir('adjuster-console') { sh 'npm ci && npm run build' } }
        }
      }
    }
    stage('Helm lint') {
      steps { sh 'helm lint deploy/helm/claims-platform' }
    }
    stage('Images') {
      when { branch 'main' }
      steps {
        script {
          for (svc in ['claim-service', 'payout-service', 'search-service', 'assessment-service', 'adjuster-console']) {
            sh "docker build -t claims/${svc}:${env.GIT_COMMIT.take(8)} ./${svc}"
          }
        }
      }
    }
  }
}
