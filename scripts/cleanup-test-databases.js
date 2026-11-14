// Run with mongosh after verification, never during a running test suite.
// Explicit names only: this script must never drop job_search_companion or system databases.
const testDatabases = [
  'job_search_companion_it',
  'job_search_companion_auth_it',
  'job_search_companion_applications_it',
  'job_search_companion_activity_it',
  'job_search_companion_actions_it',
  'job_search_companion_ui_it',
  'job_search_companion_step4_ui_it',
  'job_search_companion_step5_ui_it',
  'job_search_companion_step6_ui_it',
];
const connection = new Mongo('mongodb://127.0.0.1:27017/?directConnection=true');
const existing = new Set(connection.getDB('admin').runCommand({ listDatabases: 1, nameOnly: true })
  .databases.map(database => database.name));
for (const name of testDatabases) {
  if (!existing.has(name)) continue;
  const result = connection.getDB(name).dropDatabase();
  if (result.ok !== 1) throw new Error('Failed to remove test database: ' + name);
  print('Removed test database: ' + name);
}
print('Personal database job_search_companion was not modified.');
