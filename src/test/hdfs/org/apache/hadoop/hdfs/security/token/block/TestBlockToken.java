/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.security.token.block;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.io.TestWritable;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.ProtocolSignature;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SaslInputStream;
import org.apache.hadoop.security.SaslRpcClient;
import org.apache.hadoop.security.SaslRpcServer;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.log4j.Level;

import org.junit.Assert;
import org.junit.Test;

import static org.apache.hadoop.fs.CommonConfigurationKeys.HADOOP_SECURITY_AUTHENTICATION;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for block tokens */
public class TestBlockToken {
  public static final Log LOG = LogFactory.getLog(TestBlockToken.class);
  private static final String ADDRESS = "0.0.0.0";

  static final String SERVER_PRINCIPAL_KEY = "test.ipc.server.principal";
  private static Configuration conf;
  static {
    conf = new Configuration();
    conf.set(HADOOP_SECURITY_AUTHENTICATION, "kerberos");
    UserGroupInformation.setConfiguration(conf);
  }

  static {
    ((Log4JLogger) Client.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger) Server.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger) SaslRpcClient.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger) SaslRpcServer.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger) SaslInputStream.LOG).getLogger().setLevel(Level.ALL);
  }

  long blockKeyUpdateInterval = 10 * 60 * 1000; // 10 mins
  long blockTokenLifetime = 2 * 60 * 1000; // 2 mins
  ExtendedBlock block1 = new ExtendedBlock("0", 0L);
  ExtendedBlock block2 = new ExtendedBlock("10", 10L);
  ExtendedBlock block3 = new ExtendedBlock("-10", -108L);

  private static class getLengthAnswer implements Answer<Long> {
    BlockTokenSecretManager sm;
    BlockTokenIdentifier ident;

    public getLengthAnswer(BlockTokenSecretManager sm,
        BlockTokenIdentifier ident) {
      this.sm = sm;
      this.ident = ident;
    }

    @Override
    public Long answer(InvocationOnMock invocation) throws IOException {
      Object args[] = invocation.getArguments();
      assertEquals(1, args.length);
      ExtendedBlock block = (ExtendedBlock) args[0];
      Set<TokenIdentifier> tokenIds = UserGroupInformation.getCurrentUser()
          .getTokenIdentifiers();
      assertEquals("Only one BlockTokenIdentifier expected", 1, tokenIds.size());
      long result = 0;
      for (TokenIdentifier tokenId : tokenIds) {
        BlockTokenIdentifier id = (BlockTokenIdentifier) tokenId;
        LOG.info("Got: " + id.toString());
        assertTrue("Received BlockTokenIdentifier is wrong", ident.equals(id));
        sm.checkAccess(id, null, block, BlockTokenSecretManager.AccessMode.WRITE);
        result = id.getBlockId();
      }
      return result;
    }
  }

  private BlockTokenIdentifier generateTokenId(BlockTokenSecretManager sm,
      ExtendedBlock block, EnumSet<BlockTokenSecretManager.AccessMode> accessModes)
      throws IOException {
    Token<BlockTokenIdentifier> token = sm.generateToken(block, accessModes);
    BlockTokenIdentifier id = sm.createIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token
        .getIdentifier())));
    return id;
  }

  @Test
  public void testWritable() throws Exception {
    TestWritable.testWritable(new BlockTokenIdentifier());
    BlockTokenSecretManager sm = new BlockTokenSecretManager(true,
        blockKeyUpdateInterval, blockTokenLifetime);
    TestWritable.testWritable(generateTokenId(sm, block1, EnumSet
        .allOf(BlockTokenSecretManager.AccessMode.class)));
    TestWritable.testWritable(generateTokenId(sm, block2, EnumSet
        .of(BlockTokenSecretManager.AccessMode.WRITE)));
    TestWritable.testWritable(generateTokenId(sm, block3, EnumSet
        .noneOf(BlockTokenSecretManager.AccessMode.class)));
  }

  private void tokenGenerationAndVerification(BlockTokenSecretManager master,
      BlockTokenSecretManager slave) throws Exception {
    // single-mode tokens
    for (BlockTokenSecretManager.AccessMode mode : BlockTokenSecretManager.AccessMode
        .values()) {
      // generated by master
      Token<BlockTokenIdentifier> token1 = master.generateToken(block1,
          EnumSet.of(mode));
      master.checkAccess(token1, null, block1, mode);
      slave.checkAccess(token1, null, block1, mode);
      // generated by slave
      Token<BlockTokenIdentifier> token2 = slave.generateToken(block2,
          EnumSet.of(mode));
      master.checkAccess(token2, null, block2, mode);
      slave.checkAccess(token2, null, block2, mode);
    }
    // multi-mode tokens
    Token<BlockTokenIdentifier> mtoken = master.generateToken(block3, EnumSet
        .allOf(BlockTokenSecretManager.AccessMode.class));
    for (BlockTokenSecretManager.AccessMode mode : BlockTokenSecretManager.AccessMode
        .values()) {
      master.checkAccess(mtoken, null, block3, mode);
      slave.checkAccess(mtoken, null, block3, mode);
    }
  }

  /** test block key and token handling */
  @Test
  public void testBlockTokenSecretManager() throws Exception {
    BlockTokenSecretManager masterHandler = new BlockTokenSecretManager(true,
        blockKeyUpdateInterval, blockTokenLifetime);
    BlockTokenSecretManager slaveHandler = new BlockTokenSecretManager(false,
        blockKeyUpdateInterval, blockTokenLifetime);
    ExportedBlockKeys keys = masterHandler.exportKeys();
    slaveHandler.setKeys(keys);
    tokenGenerationAndVerification(masterHandler, slaveHandler);
    // key updating
    masterHandler.updateKeys();
    tokenGenerationAndVerification(masterHandler, slaveHandler);
    keys = masterHandler.exportKeys();
    slaveHandler.setKeys(keys);
    tokenGenerationAndVerification(masterHandler, slaveHandler);
  }

  @Test
  public void testBlockTokenRpc() throws Exception {
    BlockTokenSecretManager sm = new BlockTokenSecretManager(true,
        blockKeyUpdateInterval, blockTokenLifetime);
    Token<BlockTokenIdentifier> token = sm.generateToken(block3,
        EnumSet.allOf(BlockTokenSecretManager.AccessMode.class));

    ClientDatanodeProtocol mockDN = mock(ClientDatanodeProtocol.class);
    when(mockDN.getProtocolVersion(anyString(), anyLong())).thenReturn(
        ClientDatanodeProtocol.versionID);
    doReturn(ProtocolSignature.getProtocolSignature(
        mockDN, ClientDatanodeProtocol.class.getName(),
        ClientDatanodeProtocol.versionID, 0))
      .when(mockDN).getProtocolSignature(anyString(), anyLong(), anyInt());

    BlockTokenIdentifier id = sm.createIdentifier();
    id.readFields(new DataInputStream(new ByteArrayInputStream(token
        .getIdentifier())));
    doAnswer(new getLengthAnswer(sm, id)).when(mockDN).getReplicaVisibleLength(
        any(ExtendedBlock.class));

    final Server server = RPC.getServer(ClientDatanodeProtocol.class, mockDN,
        ADDRESS, 0, 5, true, conf, sm);

    server.start();

    final InetSocketAddress addr = NetUtils.getConnectAddress(server);
    final UserGroupInformation ticket = UserGroupInformation
        .createRemoteUser(block3.toString());
    ticket.addToken(token);

    ClientDatanodeProtocol proxy = null;
    try {
      proxy = (ClientDatanodeProtocol) RPC.getProxy(
          ClientDatanodeProtocol.class, ClientDatanodeProtocol.versionID, addr,
          ticket, conf, NetUtils.getDefaultSocketFactory(conf));
      assertEquals(block3.getBlockId(), proxy.getReplicaVisibleLength(block3));
    } finally {
      server.stop();
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
  }
  
  /** 
   * Test {@link BlockPoolTokenSecretManager}
   */
  @Test
  public void testBlockPoolTokenSecretManager() throws Exception {
    BlockPoolTokenSecretManager bpMgr = new BlockPoolTokenSecretManager();
    
    // Test BlockPoolSecretManager with upto 10 block pools
    for (int i = 0; i < 10; i++) {
      String bpid = Integer.toString(i);
      BlockTokenSecretManager masterHandler = new BlockTokenSecretManager(true,
          blockKeyUpdateInterval, blockTokenLifetime);
      BlockTokenSecretManager slaveHandler = new BlockTokenSecretManager(false,
          blockKeyUpdateInterval, blockTokenLifetime);
      bpMgr.addBlockPool(bpid, slaveHandler);
      
      
      ExportedBlockKeys keys = masterHandler.exportKeys();
      bpMgr.setKeys(bpid, keys);
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid));
      
      // Test key updating
      masterHandler.updateKeys();
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid));
      keys = masterHandler.exportKeys();
      bpMgr.setKeys(bpid, keys);
      tokenGenerationAndVerification(masterHandler, bpMgr.get(bpid));
    }
  }
  
  /**
   * This test writes a file and gets the block locations without closing
   * the file, and tests the block token in the last block. Block token is
   * verified by ensuring it is of correct kind.
   * @throws IOException
   * @throws InterruptedException
   */
  @Test
  public void testBlockTokenInLastLocatedBlock() throws IOException,
      InterruptedException {
    Configuration conf = new HdfsConfiguration();
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, true);
    conf.setInt(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, 512);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numNameNodes(1)
        .numDataNodes(1).build();
    cluster.waitActive();

    try {
      FileSystem fs = cluster.getFileSystem();
      String fileName = "/testBlockTokenInLastLocatedBlock";
      Path filePath = new Path(fileName);
      FSDataOutputStream out = fs.create(filePath, (short) 1);
      out.write(new byte[1000]);
      LocatedBlocks locatedBlocks = cluster.getNameNode().getBlockLocations(
          fileName, 0, 1000);
      while (locatedBlocks.getLastLocatedBlock() == null) {
        Thread.sleep(100);
        locatedBlocks = cluster.getNameNode().getBlockLocations(fileName, 0,
            1000);
      }
      Token<BlockTokenIdentifier> token = locatedBlocks.getLastLocatedBlock()
          .getBlockToken();
      Assert.assertEquals(BlockTokenIdentifier.KIND_NAME, token.getKind());
      out.close();
    } finally {
      cluster.shutdown();
    }
  } 
}