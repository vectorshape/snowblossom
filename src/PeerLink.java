package snowblossom;

import io.grpc.stub.StreamObserver;
import snowblossom.proto.PeerMessage;
import io.grpc.ManagedChannel;

import java.util.logging.Logger;
import java.util.logging.Level;

import java.util.Random;

import snowblossom.proto.Transaction;
import snowblossom.proto.Block;
import snowblossom.proto.BlockHeader;
import snowblossom.proto.PeerChainTip;
import snowblossom.proto.BlockSummary;


import java.util.TreeMap;

/**
 * This class exists to present a single view of a peer regardless
 * of if we are the client or server.  We don't really care.
 * Messages to the other side go out on the 'sink'.
 * Messages come in on the onNext() method.
 */
public class PeerLink implements StreamObserver<PeerMessage>
{
  private static final Logger logger = Logger.getLogger("PeerLink");

  private SnowBlossomNode node;
  private StreamObserver<PeerMessage> sink;
  private ManagedChannel channel;
  private volatile boolean closed;

  private boolean server_side;
  private String link_id;

  private PeerChainTip last_seen_tip;
  private TreeMap<Integer, ChainHash> peer_block_map = new TreeMap<Integer, ChainHash>();

  public PeerLink(SnowBlossomNode node, StreamObserver<PeerMessage> sink)
  {
    this.node = node;
    this.sink = sink;
    server_side=true;
  }

  public PeerLink(SnowBlossomNode node)
  {
    this.node = node;
    server_side=false;
  }

  private void setLinkId()
  {
    Random rnd = new Random();
    byte[] b=new byte[6];
    rnd.nextBytes(b);

    link_id = HexUtil.getHexString(b);
    
  }
  public void setSink(StreamObserver<PeerMessage> sink)
  {
    this.sink=sink;
  }
  public void setChannel(ManagedChannel channel)
  {
    this.channel = channel;
  }

  @Override
  public void onCompleted()
  {
    close();
  }

  @Override
  public void onError(Throwable t)
  {
    logger.log(Level.INFO,"link error", t);
    close();
  }

  @Override
  public void onNext(PeerMessage msg)
  {
    try
    {
      if (msg.hasTx())
      {
        Transaction tx = msg.getTx();
        if (node.getMemPool().addTransaction(tx))
        {
          node.getPeerage().broadcastTransaction(tx);
        }
      }
      else if (msg.hasTip())
      {
        PeerChainTip tip = msg.getTip();
        if (!node.getParams().getNetworkName().equals(tip.getNetworkName()))
        {
          logger.log(Level.INFO, String.format("Peer has wrong name: %s", tip.getNetworkName()));
          close();
        }
        last_seen_tip = tip;
        // TODO Think about doing some sort of update
      }
      else if (msg.hasReqBlock())
      {
        ChainHash hash = new ChainHash(msg.getReqBlock().getBlockHash());
        Block blk = node.getDB().getBlockMap().get(hash.getBytes());
        if (blk != null)
        {
          writeMessage( PeerMessage.newBuilder().setBlock(blk).build() );
        }
      }
      else if (msg.hasBlock())
      {
        Block blk = msg.getBlock();
        if (node.getBlockIngestor().ingestBlock(blk))
        { // thing about getting more blocks

        }
      }
      else if (msg.hasReqHeader())
      {
        int height = msg.getReqHeader().getBlockHeight();
        ChainHash hash = node.getDB().getBlockHashAtHeight(height);
        if (hash != null)
        {
          BlockSummary summary = node.getDB().getBlockSummaryMap().get(hash.getBytes());
          writeMessage( PeerMessage.newBuilder().setHeader(summary.getHeader()).build() );
        }
      }
      else if (msg.hasHeader())
      {
        BlockHeader header = msg.getHeader();
        peer_block_map.put(header.getBlockHeight(), new ChainHash(header.getSnowHash()));

        // Think about learning more or requesting blocks
      }

    }
    catch(ValidationException e)
    {
      logger.log(Level.INFO, "Some validation error from " + getLinkId(), e);
    }
    catch(Throwable e)
    {
      logger.log(Level.INFO, "Some bs from " + getLinkId(), e);
      close();
    }
  }

  public void close()
  {
    closed=true;
    sink.onCompleted();

    if (channel != null)
    {
      channel.shutdown();
    }
  }

  public boolean isOpen()
  {
    return !closed;
  }
  
  public void writeMessage(PeerMessage msg)
  {
    synchronized(sink)
    {
      sink.onNext(msg);
    }
  }

  public String getLinkId()
  {
    return link_id;
  }

}
