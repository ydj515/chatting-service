export function buildPsqlCommand(db, { mode, service = 'postgres' }) {
  const psqlArgs = [
    '-h',
    db.host,
    '-p',
    String(db.port),
    '-U',
    db.user,
    '-d',
    db.name,
    '-v',
    'ON_ERROR_STOP=1',
  ];

  if (mode === 'local') {
    return {
      bin: 'psql',
      args: psqlArgs,
    };
  }

  if (mode === 'docker-compose') {
    return {
      bin: 'docker',
      args: ['compose', 'exec', '-T', '-e', `PGPASSWORD=${db.password}`, service, 'psql', ...psqlArgs],
    };
  }

  throw new Error('--psql-mode must be one of local, docker-compose.');
}
