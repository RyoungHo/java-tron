/*
 * java-tron is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core;

import static org.tron.core.Constant.BLOCK_DB_NAME;
import static org.tron.core.Constant.LAST_HASH;

import com.alibaba.fastjson.JSON;
import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.utils.ByteArray;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.capsule.utils.TransactionUtil;
import org.tron.core.config.args.Args;
import org.tron.core.events.BlockchainListener;
import org.tron.core.peer.Peer;
import org.tron.protos.Protocal.Block;
import org.tron.protos.Protocal.TXInput;
import org.tron.protos.Protocal.TXOutput;
import org.tron.protos.Protocal.TXOutputs;
import org.tron.protos.Protocal.Transaction;


public class Blockchain {

  public static final String GENESIS_COINBASE_DATA = "0x10";
  public static final Logger logger = LoggerFactory.getLogger("BlockChain");
  public static String parentName = Constant.NORMAL;
  private LevelDbDataSourceImpl blockDb;

  private byte[] lastHash;
  private byte[] currentHash;

  private List<BlockchainListener> listeners = new ArrayList<>();

  /**
   * @param blockDb block database
   * @deprecated create new blockchain.
   */
  public Blockchain(@Named("block") LevelDbDataSourceImpl blockDb) {
    this.blockDb = blockDb;
    this.lastHash = blockDb.getData(LAST_HASH);

    if (this.lastHash == null) {

      GenesisBlockLoader genesisBlockLoader = buildGenesisBlockLoader();

      List<Transaction> transactions = buildTransactionsFrom(genesisBlockLoader);

      Block genesisBlock = BlockUtil.newGenesisBlock(transactions);

      //this.lastHash = new ByteArray();
      //genesisBlock.getBlockHeader().getRawData().getBlockId().toByteArray();
      this.currentHash = this.lastHash;

      persistGenesisBlockToDB(blockDb, genesisBlock);
      persistLastHash(blockDb, genesisBlock);

      addGenesisBlockToListeners(genesisBlock);
      logger.info("new blockchain");
    } else {
      this.currentHash = this.lastHash;

      logger.info("load blockchain");
    }
  }

  private void addGenesisBlockToListeners(Block genesisBlock) {
    listeners.stream().forEach(l -> l.addGenesisBlock(genesisBlock));
  }


  /**
   * @deprecated
   */
  private void persistLastHash(@Named("block") LevelDbDataSourceImpl blockDb, Block genesisBlock) {
//    byte[] lastHash = genesisBlock.getBlockHeader().getRawData().getBlockId().toByteArray();
//    blockDb.putData(LAST_HASH, lastHash);
  }

  /**
   * @deprecated
   */
  private void persistGenesisBlockToDB(@Named("block") LevelDbDataSourceImpl blockDB,
      Block genesisBlock) {
//    blockDB.putData(genesisBlock.getBlockHeader().getBlockId().toByteArray(),
//        genesisBlock.toByteArray());
  }

  private List<Transaction> buildTransactionsFrom(GenesisBlockLoader genesisBlockLoader) {
    return genesisBlockLoader
        .getTransaction().entrySet().stream().map(e -> TransactionUtil
            .newCoinbaseTransaction(e.getKey(), GENESIS_COINBASE_DATA, e.getValue()))
        .collect(Collectors.toList());
  }

  private GenesisBlockLoader buildGenesisBlockLoader() {
    InputStream is = getClass().getClassLoader().getResourceAsStream("genesis.json");
    String json = null;
    try {
      json = new String(ByteStreams.toByteArray(is));
    } catch (IOException e) {
      logger.warn("Fail to load genesis.json, error: {}", e);
    }

    return JSON.parseObject(json, GenesisBlockLoader.class);
  }

  /**
   * Checks if the database file exists.
   *
   * @return boolean
   */
  public static boolean dbExists() {
    parentName = Args.getInstance().getStorage().getDirectory();
    File file = new File(Paths.get(parentName, BLOCK_DB_NAME).toString());
    return file.exists();
  }

  /**
   * find transaction by id.
   *
   * @param id ByteString id
   * @return {@link Transaction}
   */
  public Transaction findTransaction(ByteString id) {
    Transaction transaction = Transaction.newBuilder().build();

    BlockchainIterator bi = new BlockchainIterator(this);
    while (bi.hasNext()) {
      Block block = bi.next();

      for (Transaction tx : block.getTransactionsList()) {
        String txId = ByteArray.toHexString(tx.getId().toByteArray());
        String idStr = ByteArray.toHexString(id.toByteArray());
        if (txId.equals(idStr)) {
          transaction = tx.toBuilder().build();
          return transaction;
        }
      }

      if (block.getBlockHeader().getRawData().getParentHash().isEmpty()) {
        break;
      }
    }

    return transaction;
  }

  public HashMap<String, TXOutputs> findUtxo() {
    HashMap<String, TXOutputs> utxo = new HashMap<>();
    HashMap<String, long[]> spenttxos = new HashMap<>();

    BlockchainIterator bi = new BlockchainIterator(this);
    while (bi.hasNext()) {
      Block block = bi.next();

      checkBlock(block, utxo, spenttxos);
    }

    return utxo;
  }

  private void checkBlock(Block block, HashMap<String, TXOutputs> utxo,
      HashMap<String, long[]> spenttxos) {

    for (Transaction transaction : block.getTransactionsList()) {
      checkTransaction(transaction, utxo, spenttxos);
    }
  }

  private void checkTransaction(Transaction transaction, HashMap<String, TXOutputs> utxo,
      HashMap<String, long[]> spenttxos) {

    String txid = ByteArray.toHexString(transaction.getId().toByteArray());

    for (int outIdx = 0; outIdx < transaction.getVoutList().size(); outIdx++) {
      TXOutput out = transaction.getVout(outIdx);
      checkOutput(out, outIdx, txid, utxo, spenttxos);
    }

    if (!TransactionUtil.isCoinbaseTransaction(transaction)) {
      for (TXInput in : transaction.getVinList()) {
        addInputToSpentTxos(in, spenttxos);
      }
    }
  }


  private void checkOutput(TXOutput out, int outIdx, String txid, HashMap<String, TXOutputs> utxo,
      HashMap<String, long[]> spenttxos) {
    long[] spentTx = spenttxos.get(txid);
    if (spentTx != null) {
      for (int i = 0; i < spentTx.length; i++) {
        if (spentTx[i] == outIdx) {
          return;
        }
      }
    }

    addOutputToUtxo(out, txid, utxo);
  }

  private void addOutputToUtxo(TXOutput out, String txid, HashMap<String, TXOutputs> utxo) {
    TXOutputs outs = utxo.get(txid);

    if (outs == null) {
      outs = TXOutputs.newBuilder().build();
    }

    outs = outs.toBuilder().addOutputs(out).build();
    utxo.put(txid, outs);
  }

  private void addInputToSpentTxos(TXInput in, HashMap<String, long[]> spenttxos) {
    String inTxid = ByteArray.toHexString(in.getRawData().getTxID().toByteArray());
    long[] vindexs = spenttxos.get(inTxid);

    if (vindexs == null) {
      vindexs = new long[0];
    }

    vindexs = Arrays.copyOf(vindexs, vindexs.length + 1);
    vindexs[vindexs.length - 1] = in.getRawData().getVout();

    spenttxos.put(inTxid, vindexs);
  }

  /**
   * add a block into database.
   *
   * @deprecated
   */
  public void addBlock(Block block) {
//    byte[] blockInDB = blockDb.getData(block.getBlockHeader().getBlockId().toByteArray());
//
//    if (blockInDB == null || blockInDB.length == 0) {
//      return;
//    }
//
//    persistGenesisBlockToDB(blockDb, block);
//
//    byte[] lastHash = blockDb.getData(ByteArray.fromString("lashHash"));
//    byte[] lastBlockData = blockDb.getData(lastHash);
//    try {
//      Block lastBlock = Block.parseFrom(lastBlockData);
//      if (block.getBlockHeader().getNumber() > lastBlock.getBlockHeader().getNumber()) {
//        blockDb.putData(ByteArray.fromString("lashHash"),
//            block.getBlockHeader().getBlockId().toByteArray());
//        this.lastHash = block.getBlockHeader().getBlockId().toByteArray();
//        this.currentHash = this.lastHash;
//      }
//    } catch (InvalidProtocolBufferException e) {
//      e.printStackTrace();
//    }
  }

//  public Transaction signTransaction(Transaction transaction, ECKey myKey) {
//    HashMap<String, Transaction> prevTXs = new HashMap<>();
//
//    for (TXInput txInput : transaction.getVinList()) {
//      ByteString txId = txInput.getRawData().getTxID();
//      Transaction prevTX = this.findTransaction(txId).toBuilder().build();
//      String key = ByteArray.toHexString(txId.toByteArray());
//      prevTXs.put(key, prevTX);
//    }
//
//    // transaction = TransactionCapsule.sign(transaction, myKey, prevTXs);
//    transaction = TransactionUtil.sign(transaction, myKey);// Unsupport muilty address, needn't
//    // input prevTXs
//    return transaction;
//  }

  /**
   * add a block.
   */
  public void addBlock(List<Transaction> transactions) {
    // get lastHash
    byte[] lastHash = blockDb.getData(LAST_HASH);
    ByteString parentHash = ByteString.copyFrom(lastHash);
    // get number
    long number = BlockUtil.getIncreaseNumber(this);
    // get difficulty
    ByteString difficulty = ByteString.copyFromUtf8(Constant.DIFFICULTY);
    Block block = BlockUtil.newBlock(transactions, parentHash, difficulty, number);

    for (BlockchainListener listener : listeners) {
      listener.addBlock(block);
    }
  }

  /**
   * receive a block and save it into database,update caching at the same time.
   *
   * @param block block
   * @param utxoSet utxoSet
   * @deprecated
   */
  public void receiveBlock(Block block, UTXOSet utxoSet, Peer peer) {

//    byte[] lastHashKey = LAST_HASH;
//    byte[] lastHash = blockDb.getData(lastHashKey);
//
//    if (!ByteArray.toHexString(block.getBlockHeader().getParentHash().toByteArray())
//        .equals(ByteArray.toHexString(lastHash))) {
//      return;
//    }
//
//    // save the block into the database
//    byte[] blockHashKey = block.getBlockHeader().getBlockId().toByteArray();
//    byte[] blockVal = block.toByteArray();
//    blockDb.putData(blockHashKey, blockVal);
//
//    byte[] ch = block.getBlockHeader().getBlockId().toByteArray();
//
//    // update lastHash
//    peer.getBlockchain().getBlockDB().putData(lastHashKey, ch);
//
//    this.lastHash = ch;
//    currentHash = ch;
//    System.out.println(BlockUtil.toPrintString(block));
//    // update UTXO cache
//    utxoSet.reindex();
  }

  public void addListener(BlockchainListener listener) {
    this.listeners.add(listener);
  }

  public LevelDbDataSourceImpl getBlockDB() {
    return blockDb;
  }

  public void setBlockDB(LevelDbDataSourceImpl blockDB) {
    this.blockDb = blockDB;
  }

  public byte[] getLastHash() {
    return lastHash;
  }

  public void setLastHash(byte[] lastHash) {
    this.lastHash = lastHash;
  }

  public byte[] getCurrentHash() {
    return currentHash;
  }

  public void setCurrentHash(byte[] currentHash) {
    this.currentHash = currentHash;
  }
}
