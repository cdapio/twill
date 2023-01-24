/*
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
package org.apache.twill.internal.yarn;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.twill.api.LocalFile;
import org.apache.twill.filesystem.FileContextLocationFactory;
import org.apache.twill.filesystem.ForwardingLocationFactory;
import org.apache.twill.filesystem.LocationFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;


/**
 * Collection of helper methods to simplify YARN calls.
 */
public class YarnUtils {

  private static final Logger LOG = LoggerFactory.getLogger(YarnUtils.class);


  /**
   * Defines different versions of Hadoop.
   */
  public enum HadoopVersions {
    HADOOP_21,
    HADOOP_22,
    HADOOP_23,
    HADOOP_26
  }

  private static final AtomicReference<HadoopVersions> HADOOP_VERSION = new AtomicReference<>();

  public static YarnLocalResource createLocalResource(LocalFile localFile) {
    Preconditions.checkArgument(localFile.getLastModified() >= 0, "Last modified time should be >= 0.");
    Preconditions.checkArgument(localFile.getSize() >= 0, "File size should be >= 0.");

    YarnLocalResource resource = new Hadoop21YarnLocalResource();
    resource.setVisibility(LocalResourceVisibility.APPLICATION);
    resource.setResource(ConverterUtils.getYarnUrlFromURI(localFile.getURI()));
    resource.setTimestamp(localFile.getLastModified());
    resource.setSize(localFile.getSize());
    return setLocalResourceType(resource, localFile);
  }

  public static YarnLaunchContext createLaunchContext() {
    return new Hadoop21YarnLaunchContext();
  }

  // temporary workaround since older versions of hadoop don't have the getVirtualCores method.
  public static int getVirtualCores(Resource resource) {
    try {
      Method getVirtualCores = Resource.class.getMethod("getVirtualCores");
      return (Integer) getVirtualCores.invoke(resource);
    } catch (Exception e) {
      return 0;
    }
  }

  /**
   * Temporary workaround since older versions of hadoop don't have the setCores method.
   *
   * @param resource
   * @param cores
   * @return true if virtual cores was set, false if not.
   */
  public static boolean setVirtualCores(Resource resource, int cores) {
    try {
      Method setVirtualCores = Resource.class.getMethod("setVirtualCores", int.class);
      setVirtualCores.invoke(resource, cores);
    } catch (Exception e) {
      // It's ok to ignore this exception, as it's using older version of API.
      return false;
    }
    return true;
  }

  /**
   * Helper method to get delegation tokens for the given LocationFactory.
   * @param config The hadoop configuration.
   * @param locationFactory The LocationFactory for generating tokens.
   * @param credentials Credentials for storing tokens acquired.
   * @return List of delegation Tokens acquired.
   */
  public static List<Token<?>> addDelegationTokens(Configuration config,
                                                   LocationFactory locationFactory,
                                                   Credentials credentials) throws IOException {
    if (!UserGroupInformation.isSecurityEnabled()) {
      LOG.debug("Security is not enabled");
      return ImmutableList.of();
    }

    try (FileSystem fileSystem = getFileSystem(locationFactory)) {
      if (fileSystem == null) {
        return ImmutableList.of();
      }

      String renewer = YarnUtils.getYarnTokenRenewer(config);

      Token<?>[] tokens = fileSystem.addDelegationTokens(renewer, credentials);
      LOG.debug("Added HDFS DelegationTokens: {}", Arrays.toString(tokens));

      return tokens == null ? ImmutableList.<Token<?>>of() : ImmutableList.copyOf(tokens);
    }
  }

  private static Object invokeStaticMethodWithExceptionHandled(Method method, Object ... args) {
    Preconditions.checkNotNull(method);
    try {
      return method.invoke(null, args);
    } catch (Exception e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(e.getMessage(), e);
      }
      throw Throwables.propagate(e);
    }
  }

  /**
   * Encodes the given {@link Credentials} as bytes.
   */
  public static ByteBuffer encodeCredentials(Credentials credentials) {
    try {
      DataOutputBuffer out = new DataOutputBuffer();
      credentials.writeTokenStorageToStream(out);
      return ByteBuffer.wrap(out.getData(), 0, out.getLength());
    } catch (IOException e) {
      // Shouldn't throw
      LOG.error("Failed to encode Credentials.", e);
      throw Throwables.propagate(e);
    }
  }

  /**
   * Decodes {@link Credentials} from the given buffer.
   * If the buffer is null or empty, it returns an empty Credentials.
   */
  public static Credentials decodeCredentials(ByteBuffer buffer) throws IOException {
    Credentials credentials = new Credentials();
    if (buffer != null && buffer.hasRemaining()) {
      DataInputByteBuffer in = new DataInputByteBuffer();
      in.reset(buffer);
      credentials.readTokenStorageStream(in);
    }
    return credentials;
  }

  public static String getYarnTokenRenewer(Configuration config) throws IOException {
    String rmHost = getRMAddress(config).getHostName();
    String renewer = SecurityUtil.getServerPrincipal(config.get(YarnConfiguration.RM_PRINCIPAL), rmHost);

    if (renewer == null || renewer.length() == 0) {
      throw new IOException("No Kerberos principal for Yarn RM to use as renewer");
    }

    return renewer;
  }

  public static InetSocketAddress getRMAddress(Configuration config) {
    return config.getSocketAddr(YarnConfiguration.RM_ADDRESS,
                                YarnConfiguration.DEFAULT_RM_ADDRESS,
                                YarnConfiguration.DEFAULT_RM_PORT);
  }

  /**
   * Returns {@link org.apache.twill.internal.yarn.YarnUtils.HadoopVersions} for the current build profile,
   * depending on the classes in the classpath.
   * @return The version of Hadoop for the current build profile.
   */
  public static HadoopVersions getHadoopVersion() {
    HadoopVersions hadoopVersion = HADOOP_VERSION.get();
    if (hadoopVersion != null) {
      return hadoopVersion;
    }
    try {
      Class.forName("org.apache.hadoop.yarn.client.cli.LogsCLI");
      try {
        Class.forName("org.apache.hadoop.yarn.conf.HAUtil");
        try {
          Class[] args = new Class[1];
          args[0] = String.class;
          // see if we have a org.apache.hadoop.yarn.api.records.ContainerId.fromString() method
          Class.forName("org.apache.hadoop.yarn.api.records.ContainerId").getMethod("fromString", args);
          HADOOP_VERSION.set(HadoopVersions.HADOOP_26);
        } catch (NoSuchMethodException e) {
          HADOOP_VERSION.set(HadoopVersions.HADOOP_23);
        }
      } catch (ClassNotFoundException e) {
        HADOOP_VERSION.set(HadoopVersions.HADOOP_22);
      }
    } catch (ClassNotFoundException e) {
      HADOOP_VERSION.set(HadoopVersions.HADOOP_21);
    }
    return HADOOP_VERSION.get();
  }

  private static YarnLocalResource setLocalResourceType(YarnLocalResource localResource, LocalFile localFile) {
    if (localFile.isArchive()) {
      if (localFile.getPattern() == null) {
        localResource.setType(LocalResourceType.ARCHIVE);
      } else {
        localResource.setType(LocalResourceType.PATTERN);
        localResource.setPattern(localFile.getPattern());
      }
    } else {
      localResource.setType(LocalResourceType.FILE);
    }
    return localResource;
  }

  /**
   * Gets the Hadoop {@link FileSystem} from LocationFactory.
   *
   * @return the Hadoop {@link FileSystem} that represents the filesystem used by the given {@link LocationFactory};
   *         {@code null} will be returned if unable to determine the {@link FileSystem}.
   */
  @Nullable
  private static FileSystem getFileSystem(LocationFactory locationFactory) throws IOException {
    if (locationFactory instanceof ForwardingLocationFactory) {
      return getFileSystem(((ForwardingLocationFactory) locationFactory).getDelegate());
    }
    // Due to HDFS-10296, for encrypted file systems, FileContext does not acquire the KMS delegation token
    // Since we know we are in Yarn, it is safe to get the FileSystem directly, bypassing LocationFactory.
    if (locationFactory instanceof FileContextLocationFactory) {
      // Disable caching of FileSystem object, as the FileSystem object is only used to get delegation token for the
      // current user. Caching it may causes leaking of FileSystem object if the method is called with different users.
      Configuration config = new Configuration(((FileContextLocationFactory) locationFactory).getConfiguration());
      String scheme = FileSystem.getDefaultUri(config).getScheme();
      config.set(String.format("fs.%s.impl.disable.cache", scheme), "true");
      return FileSystem.get(config);
    }

    LOG.warn("Unexpected: LocationFactory is not backed by FileContextLocationFactory");
    return null;
  }


  private YarnUtils() {
  }
}
