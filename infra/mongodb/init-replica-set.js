// Initialization is safe to rerun: an existing replica set is never reset.
let ready = false;
for (let attempt = 0; attempt < 60; attempt++) {
  try {
    const connection = new Mongo('mongodb://127.0.0.1:27017/?directConnection=true');
    const admin = connection.getDB('admin');
    try {
      const status = admin.runCommand({ replSetGetStatus: 1 });
      if (status.ok !== 1) throw status;
    } catch (error) {
      // Only an uninitialized set should be initialized; other errors matter.
      if (error.code !== 94 && error.codeName !== 'NotYetInitialized') throw error;
      const result = admin.runCommand({
        replSetInitiate: { _id: 'rs0', members: [{ _id: 0, host: 'localhost:27017' }] },
      });
      if (result.ok !== 1) throw result;
    }
    if (admin.runCommand({ hello: 1 }).isWritablePrimary) {
      ready = true;
      print('Replica set rs0 is ready for transactions.');
      break;
    }
  } catch (error) {
    print('Waiting for MongoDB: ' + error.message);
  }
  sleep(1000);
}
// Return a nonzero exit code so Compose exposes setup failures.
if (!ready) { print('MongoDB did not become primary within 60 attempts.'); quit(1); }
