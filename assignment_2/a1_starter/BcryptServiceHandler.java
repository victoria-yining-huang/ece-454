import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.mindrot.jbcrypt.BCrypt;

public class BcryptServiceHandler implements BcryptService.Iface {

    private boolean _isBENode;
    private final ExecutorService hashService = Executors.newFixedThreadPool(4);

    public BcryptServiceHandler(boolean isBENode){
        _isBENode = isBENode;
    }

    public List<String> hashPassword(List<String> passwords, short logRounds) throws IllegalArgument, org.apache.thrift.TException
    {
        TTransport transport = null;
        List<String> res = new ArrayList<>();

        // If BENode, then compute the hash right here
        if (_isBENode) {
            BatchTracker.receivedBatch();
            try {
                int size = passwords.size();
                int chunkSize = size / 4;
                List<Future<List<String>>> futures = new ArrayList<>();
                System.out.println("multithreaded hit");
                for (int i = 0; i < 4; i++) {
                    int startInd = i * chunkSize;
                    int endInd = i == 3 ? size : (i + 1) * chunkSize;
                    MultiThreadHash  myCallable = new MultiThreadHash(passwords.subList(startInd, endInd), logRounds);
                    futures.add(hashService.submit(myCallable));
                }
                for (Future<List<String>> f: futures) {
                    res.addAll(f.get());
                }
                // we update the timer of the receivedBatch because we don't
                // want the time it took to process the batch as a part of the
                // timeout
                BatchTracker.receivedBatch();
                System.out.println("sizes: " + passwords.size() + " " + res.size());
                return res;
            } catch (Exception e) {
                throw new IllegalArgument(e.getMessage());
            }

        } else {
            Integer availableIndex = NodeManager.getAvailableNodeIndex();

            // All BENodes were busy, compute the hash by the FENode
            if (availableIndex == null) {
                System.out.println("All BENodes are busy");
                try {
                    return hashPasswordImpl(passwords, logRounds);
                } catch (Exception e) {
                    throw new IllegalArgument(e.getMessage());
                }
            }


            while (availableIndex != null) {

                // This is an FENode, try offloading to the BENode
                BcryptService.Client client = NodeManager.getNodeClient(availableIndex);
                transport = NodeManager.getNodeTransport(availableIndex);

                // if the client and transport of a BE node is available then have FE offload the work to the
                // BE Node
                System.out.println("moving work over to the back end node");
                try {
                    transport.open();
                    NodeManager.markUnavailable(availableIndex);
                    List<String> BEResult = client.hashPassword(passwords, logRounds);
                    NodeManager.markAvailable(availableIndex);

                    return BEResult;
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                    // if BENode threw an exception, then we simply remove it from NodeManager
                    NodeManager.removeNode(availableIndex);
                    System.out.println("BENode at " + availableIndex + " is dead :( Removing from NodeManager");
                    availableIndex = NodeManager.getAvailableNodeIndex();
                } finally {
                    if (transport != null) {
                        transport.close();
                    }
                }
            }

            // We tried to offload  the work to each available BENode, but they all failed
            // therefore, have the FENode do the work
            System.out.println("All BENodes are dead");
            try {
                return hashPasswordImpl(passwords, logRounds);
            } catch (Exception ex) {
                throw new IllegalArgument(ex.getMessage());
            }
        }
    }

    public List<Boolean> checkPassword(List<String> passwords, List<String> hashes) throws IllegalArgument, org.apache.thrift.TException
    {
        try {

            // throw error if the size of hashes
            if (passwords.size() != hashes.size()) {
                throw new Exception("passwords and hashes are not equal. bitch, wtf you trying to do here?");
            }

            List<Boolean> ret = new ArrayList<>();

            String password;
            String hash;
            for (int i = 0; i < passwords.size(); i++) {
                password = passwords.get(i);
                hash = hashes.get(i);
                ret.add(BCrypt.checkpw(password, hash));
            }

            return ret;
        } catch (Exception e) {
            throw new IllegalArgument(e.getMessage());
        }
    }
    
    public Map<String, String> heartBeat(String hostname, String port) throws IllegalArgument, org.apache.thrift.TException {
        System.out.println("received heart beat");
        System.out.println(hostname + port);
      try {
          TSocket sock = new TSocket(hostname, Integer.parseInt(port));
          TTransport transport = new TFramedTransport(sock);
          TProtocol protocol = new TBinaryProtocol(transport);
          BcryptService.Client client = new BcryptService.Client(protocol);

          String nodeId = hostname + port;

          if (!NodeManager.isRegistered(nodeId)) {
              NodeManager.addNode(client, transport);
              NodeManager.registerNode(nodeId);
          }

          HashMap<String, String> map = new HashMap<>();
          map.put(hostname, port);
          return map;

      } catch (Exception e) {
          e.printStackTrace();
          throw new IllegalArgument(e.getMessage());
      }
    }

    private List<String> hashPasswordImpl(List<String> passwords, short logRounds) throws Exception {
        System.out.println("Hashing Passwords of size: " + passwords.size());

        List<String> ret = new ArrayList<>();
        String hashedPassword;

        for (String password : passwords) {
            hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt(logRounds));
            ret.add(hashedPassword);
        }

        return ret;
    }

    class MultiThreadHash implements Callable<List<String>> {
        private List<String> _passwords;
        short _logRounds;

        public MultiThreadHash(List<String> passwords, short logRounds) {
            _passwords = passwords;
            _logRounds = logRounds;
        }

        @Override
        public List<String> call() throws Exception{
            List<String> hashes = hashPasswordImpl(_passwords, _logRounds);
            return hashes;
        }
    }
}
