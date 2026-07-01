#!/usr/bin/env node
import {
  parsePagerDutyOffSmokeArgs,
  runPagerDutyOffSmoke,
} from './lib/alertmanagerPagerDutyOffSmokePlan.mjs';

async function main() {
  const options = parsePagerDutyOffSmokeArgs(process.argv.slice(2), process.env);
  const result = await runPagerDutyOffSmoke(options);
  console.log(JSON.stringify(result, null, 2));
  if (!result.ok) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
