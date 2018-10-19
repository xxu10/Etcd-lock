import com.coreos.jetcd.Client;
import com.coreos.jetcd.KV;
import com.coreos.jetcd.Lease;
import com.coreos.jetcd.Watch;
import com.coreos.jetcd.data.ByteSequence;
import com.coreos.jetcd.data.KeyValue;
import com.coreos.jetcd.kv.PutResponse;
import com.coreos.jetcd.options.GetOption;
import com.coreos.jetcd.options.PutOption;
import com.coreos.jetcd.watch.WatchEvent;
import com.coreos.jetcd.watch.WatchResponse;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class EtcdLock {
    public static void main(String[] args) throws InterruptedException, ExecutionException,
            TimeoutException, ClassNotFoundException
    {

        Client client = Client.builder().endpoints("http://localhost:2379").build();

        String lockName = "/lock/mutexlock";


        for (int i = 0; i < 5; i++)
        {
            new EtcdLock.MyThread(lockName, client).start();
        }
    }


    public static String lock(String lockName, Client client, long leaseId)
    {

        StringBuffer strBufOfRealKey = new StringBuffer();
        strBufOfRealKey.append(lockName);
        strBufOfRealKey.append("/");
        strBufOfRealKey.append(UUID.randomUUID().toString());

        long revisionOfMyself = 0L;

        KV kvClient = client.getKVClient();

        try
        {
            PutResponse putResponse = kvClient
                    .put(ByteSequence.fromString(strBufOfRealKey.toString()),
                            ByteSequence.fromString("value"),
                            PutOption.newBuilder().withLeaseId(leaseId).build())
                    .get(10, TimeUnit.SECONDS);


            revisionOfMyself = putResponse.getHeader().getRevision();
        }
        catch (InterruptedException | ExecutionException | TimeoutException e1)
        {
            System.out.println("[error]: lock operation failed:" + e1);
        }

        try
        {

            List<KeyValue> kvList = kvClient.get(ByteSequence.fromString(lockName),
                    GetOption.newBuilder().withPrefix(ByteSequence.fromString(lockName))
                            .withSortField(GetOption.SortTarget.MOD).build())
                    .get().getKvs();


            if (revisionOfMyself == kvList.get(0).getModRevision())
            {
                System.out.println("[lock]: lock successfully. [revision]:" + revisionOfMyself);
                return strBufOfRealKey.toString();
            }
            else
            {

                int preIndex = 0;
                for (int index = 0; index < kvList.size(); index++)
                {
                    if (kvList.get(index).getModRevision() == revisionOfMyself)
                    {
                        preIndex = index - 1;
                    }
                }

                ByteSequence preKeyBS = kvList.get(preIndex).getKey();

                Watch.Watcher watcher = client.getWatchClient().watch(preKeyBS);
                WatchResponse res = null;

                try
                {
                    System.out.println("[lock]: keep waiting until the lock is released.");
                    res = watcher.listen();
                }
                catch (InterruptedException e)
                {
                    System.out.println("[error]: failed to listen key.");
                }


                List<WatchEvent> eventlist = res.getEvents();
                for (WatchEvent event : eventlist)
                {
                    if (event.getEventType().toString().equals("DELETE"))
                    {
                        System.out.println("[lock]: lock successfully. [revision]:" + revisionOfMyself);
                        return strBufOfRealKey.toString();
                    }
                }
            }
        }
        catch (InterruptedException | ExecutionException e)
        {
            System.out.println("[error]: lock operation failed:" + e);
        }

        return strBufOfRealKey.toString();
    }


    public static void unLock(String realLockName, Client client)
    {
        try
        {
            client.getKVClient().delete(ByteSequence.fromString(realLockName)).get(10,
                    TimeUnit.SECONDS);
            System.out.println("[unLock]: unlock successfully.[lockName]:" + realLockName);
        }
        catch (InterruptedException | ExecutionException | TimeoutException e)
        {
            System.out.println("[error]: unlock failed：" + e);
        }
    }


    public static class MyThread extends Thread
    {
        private String lockName;
        private Client client;

        MyThread(String lockName, Client client)
        {
            this.client = client;
            this.lockName = lockName;
        }

        @Override
        public void run()
        {
            Lease leaseClient = client.getLeaseClient();
            Long leaseId = null;
            try
            {
                leaseId = leaseClient.grant(15).get(10, TimeUnit.SECONDS).getID();
            }
            catch (InterruptedException | ExecutionException | TimeoutException e1)
            {
                System.out.println("[error]: create lease failed:" + e1);
                return;
            }

            ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor();
            service.scheduleAtFixedRate(new EtcdLock.KeepAliveTask(leaseClient, leaseId), 1, 12, TimeUnit.SECONDS);

            // 1. try to lock
            String realLoclName = lock(lockName, client, leaseId);

            // 2. to do something
            try
            {
                Thread.sleep(6000);
            }
            catch (InterruptedException e2)
            {
                System.out.println("[error]:" + e2);
            }
            // 3. unlock
            service.shutdown();
            unLock(realLoclName, client);
        }
    }


    public static class KeepAliveTask implements Runnable
    {
        private Lease leaseClient;
        private long leaseId;

        KeepAliveTask(Lease leaseClient, long leaseId)
        {
            this.leaseClient = leaseClient;
            this.leaseId = leaseId;
        }

        @Override
        public void run()
        {
            leaseClient.keepAliveOnce(leaseId);
        }
    }
}
