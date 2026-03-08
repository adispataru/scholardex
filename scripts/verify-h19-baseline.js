#!/usr/bin/env node

const { execSync } = require('child_process');

const testSelectors = [
  '*ScopusCutoverGuardrailTest',
  '*ScholardexPublicationCanonicalizationServiceTest',
  '*ScholardexCitationCanonicalizationServiceTest',
  '*WosScholardexOnboardingServiceTest',
  '*PublicationWizardFacadeTest',
  '*ScholardexSourceLinkServiceTest',
  '*ScholardexEdgeWriterServiceTest',
  '*ScholardexEdgeReconciliationServiceTest',
  '*AdminConflictControllerContractTest',
  '*AdminConflictControllerSecurityContractTest',
  '*AdminSourceLinkControllerContractTest',
  '*AdminSourceLinkControllerSecurityContractTest'
];

const args = testSelectors
  .map((selector) => `--tests "${selector}"`)
  .join(' ');

const command = `./gradlew test ${args}`;

try {
  execSync(command, { stdio: 'inherit' });
  console.log('H19 baseline verification passed.');
} catch (error) {
  console.error('H19 baseline verification failed.');
  process.exit(error.status || 1);
}
