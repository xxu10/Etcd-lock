# Etcd-lock
Etcd distributed lock 

Etcd is a distributed reliable key-value store for the most critical data of a distributed system (https://github.com/etcd-io/etcd).
Etcd uses the Raft consensus algorithm to manage a highly-available replicated log.

Every key has a revision number, after each transaction, the revision number will increase 1 to keep each operation in order. When a lock is released,
multiple threads will compete for the resource, however, they can only get the lock as the sequence of revision number, so thundering herd problem will not happen.

In case of the holder can not release the lock. We need Time to live (TTL) mechanism to auto release lock.
