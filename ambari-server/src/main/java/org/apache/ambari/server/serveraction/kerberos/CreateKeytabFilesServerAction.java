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

package org.apache.ambari.server.serveraction.kerberos;

import com.google.inject.Inject;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.configuration.Configuration;
import org.apache.ambari.server.orm.dao.KerberosPrincipalDAO;
import org.apache.ambari.server.orm.dao.KerberosPrincipalHostDAO;
import org.apache.ambari.server.orm.entities.KerberosPrincipalEntity;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.directory.server.kerberos.shared.keytab.Keytab;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import static org.apache.ambari.server.serveraction.kerberos.KerberosActionDataFile.HOSTNAME;
import static org.apache.ambari.server.serveraction.kerberos.KerberosActionDataFile.KEYTAB_FILE_IS_CACHABLE;
import static org.apache.ambari.server.serveraction.kerberos.KerberosActionDataFile.KEYTAB_FILE_PATH;

/**
 * CreateKeytabFilesServerAction is a ServerAction implementation that creates keytab files as
 * instructed.
 * <p/>
 * This class mainly relies on the KerberosServerAction to iterate through metadata identifying
 * the Kerberos keytab files that need to be created. For each identity in the metadata, this
 * implementation's
 * {@link KerberosServerAction#processIdentity(java.util.Map, String, KerberosOperationHandler, java.util.Map)}
 * is invoked attempting the creation of the relevant keytab file.
 */
public class CreateKeytabFilesServerAction extends KerberosServerAction {
  private final static Logger LOG = LoggerFactory.getLogger(CreateKeytabFilesServerAction.class);

  /**
   * KerberosPrincipalDAO used to set and get Kerberos principal details
   */
  @Inject
  private KerberosPrincipalDAO kerberosPrincipalDAO;

  /**
   * KerberosPrincipalHostDAO used to get Kerberos principal details
   */
  @Inject
  private KerberosPrincipalHostDAO kerberosPrincipalHostDAO;

  /**
   * Configuration used to get the configured properties such as the keytab file cache directory
   */
  @Inject
  private Configuration configuration;

  /**
   * A map of data used to track what has been processed in order to optimize the creation of keytabs
   * such as knowing when to create a cached keytab file or use a cached keytab file.
   */
  Map<String, Set<String>> visitedIdentities = new HashMap<String, Set<String>>();

  /**
   * Called to execute this action.  Upon invocation, calls
   * {@link org.apache.ambari.server.serveraction.kerberos.KerberosServerAction#processIdentities(java.util.Map)} )}
   * to iterate through the Kerberos identity metadata and call
   * {@link org.apache.ambari.server.serveraction.kerberos.CreateKeytabFilesServerAction#processIdentities(java.util.Map)}
   * for each identity to process.
   *
   * @param requestSharedDataContext a Map to be used a shared data among all ServerActions related
   *                                 to a given request
   * @return a CommandReport indicating the result of this action
   * @throws AmbariException
   * @throws InterruptedException
   */
  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext) throws
      AmbariException, InterruptedException {
    return processIdentities(requestSharedDataContext);
  }


  /**
   * For each identity, create a keytab and append to a new or existing keytab file.
   * <p/>
   * It is expected that the {@link org.apache.ambari.server.serveraction.kerberos.CreatePrincipalsServerAction}
   * (or similar) has executed before this action and a set of passwords has been created, map to
   * their relevant (evaluated) principals and stored in the requestSharedDataContext.
   * <p/>
   * If a password exists for the current evaluatedPrincipal, use a
   * {@link org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandler} to generate
   * the keytab file. To help avoid filename collisions and to build a structure that is easy to
   * discover, each keytab file is stored in host-specific
   * ({@link org.apache.ambari.server.serveraction.kerberos.KerberosActionDataFile#HOSTNAME})
   * directory using the SHA1 hash of its destination file path
   * ({@link org.apache.ambari.server.serveraction.kerberos.KerberosActionDataFile#KEYTAB_FILE_PATH})
   * <p/>
   * <pre>
   *   data_directory
   *   |- host1
   *   |  |- 16a054404c8826cd604a27ac970e8cc4b9c7a3fa   (keytab file)
   *   |  |- ...                                        (keytab files)
   *   |  |- a3c09cae73406912e8c55296d1c85b674d24f576   (keytab file)
   *   |- host2
   *   |  |- ...
   * </pre>
   *
   * @param identityRecord           a Map containing the data for the current identity record
   * @param evaluatedPrincipal       a String indicating the relevant principal
   * @param operationHandler         a KerberosOperationHandler used to perform Kerberos-related
   *                                 tasks for specific Kerberos implementations
   *                                 (MIT, Active Directory, etc...)
   * @param requestSharedDataContext a Map to be used a shared data among all ServerActions related
   *                                 to a given request
   * @return a CommandReport, indicating an error condition; or null, indicating a success condition
   * @throws AmbariException if an error occurs while processing the identity record
   */
  @Override
  protected CommandReport processIdentity(Map<String, String> identityRecord, String evaluatedPrincipal,
                                          KerberosOperationHandler operationHandler,
                                          Map<String, Object> requestSharedDataContext)
      throws AmbariException {
    CommandReport commandReport = null;

    if (identityRecord != null) {
      String message;

      if (operationHandler == null) {
        message = String.format("Failed to create keytab file for %s, missing KerberosOperationHandler", evaluatedPrincipal);
        actionLog.writeStdErr(message);
        LOG.error(message);
        commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
      } else {
        Map<String, String> principalPasswordMap = getPrincipalPasswordMap(requestSharedDataContext);
        Map<String, Integer> principalKeyNumberMap = getPrincipalKeyNumberMap(requestSharedDataContext);

        String host = identityRecord.get(HOSTNAME);
        String keytabFilePath = identityRecord.get(KEYTAB_FILE_PATH);

        if ((host != null) && !host.isEmpty() && (keytabFilePath != null) && !keytabFilePath.isEmpty()) {
          Set<String> visitedPrincipalKeys = visitedIdentities.get(evaluatedPrincipal);
          String visitationKey = String.format("%s|%s", host, keytabFilePath);

          if ((visitedPrincipalKeys == null) || !visitedPrincipalKeys.contains(visitationKey)) {
            // Look up the current evaluatedPrincipal's password.
            // If found create the keytab file, else try to find it in the cache.
            String password = principalPasswordMap.get(evaluatedPrincipal);

            message = String.format("Creating keytab file for %s on host %s", evaluatedPrincipal, host);
            LOG.info(message);
            actionLog.writeStdOut(message);

            // Determine where to store the keytab file.  It should go into a host-specific
            // directory under the previously determined data directory.
            File hostDirectory = new File(getDataDirectoryPath(), host);

            // Ensure the host directory exists...
            if (!hostDirectory.exists() && hostDirectory.mkdirs()) {
              // Make sure only Ambari has access to this directory.
              ensureAmbariOnlyAccess(hostDirectory);
            }

            if (hostDirectory.exists()) {
              File destinationKeytabFile = new File(hostDirectory, DigestUtils.sha1Hex(keytabFilePath));

              if (password == null) {
                if (kerberosPrincipalHostDAO.exists(evaluatedPrincipal, host)) {
                  // There is nothing to do for this since it must already exist and we don't want to
                  // regenerate the keytab
                  message = String.format("Skipping keytab file for %s, missing password indicates nothing to do", evaluatedPrincipal);
                  LOG.debug(message);
                } else {
                  KerberosPrincipalEntity principalEntity = kerberosPrincipalDAO.find(evaluatedPrincipal);
                  String cachedKeytabPath = (principalEntity == null) ? null : principalEntity.getCachedKeytabPath();

                  if (cachedKeytabPath == null) {
                    message = String.format("Failed to create keytab for %s, missing cached file", evaluatedPrincipal);
                    actionLog.writeStdErr(message);
                    LOG.error(message);
                    commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
                  } else {
                    try {
                      operationHandler.createKeytabFile(new File(cachedKeytabPath), destinationKeytabFile);
                    } catch (KerberosOperationException e) {
                      message = String.format("Failed to create keytab file for %s - %s", evaluatedPrincipal, e.getMessage());
                      actionLog.writeStdErr(message);
                      LOG.error(message, e);
                      commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
                    }
                  }
                }
              } else {
                Keytab keytab = null;

                // Possibly get the keytab from the cache
                if (visitedPrincipalKeys != null) {
                  // Since we have visited this principal before, attempt to pull the keytab from the
                  // cache...
                  KerberosPrincipalEntity principalEntity = kerberosPrincipalDAO.find(evaluatedPrincipal);
                  String cachedKeytabPath = (principalEntity == null) ? null : principalEntity.getCachedKeytabPath();

                  if (cachedKeytabPath != null) {
                    try {
                      keytab = Keytab.read(new File(cachedKeytabPath));
                    } catch (IOException e) {
                      message = String.format("Failed to read the cached keytab for %s, recreating if possible - %s",
                          evaluatedPrincipal, e.getMessage());

                      if (LOG.isDebugEnabled()) {
                        LOG.warn(message, e);
                      } else {
                        LOG.warn(message, e);
                      }
                    }
                  }
                }

                // If the keytab was not retrieved from the cache... create it.
                if (keytab == null) {
                  Integer keyNumber = principalKeyNumberMap.get(evaluatedPrincipal);

                  try {
                    keytab = operationHandler.createKeytab(evaluatedPrincipal, password, keyNumber);

                    // If the current identity does not represent a service, copy it to a secure location
                    // and store that location so it can be reused rather than recreate it.
                    KerberosPrincipalEntity principalEntity = kerberosPrincipalDAO.find(evaluatedPrincipal);
                    if (principalEntity != null) {
                      if (!principalEntity.isService() && ("true".equalsIgnoreCase(identityRecord.get(KEYTAB_FILE_IS_CACHABLE)))) {
                        File cachedKeytabFile = cacheKeytab(evaluatedPrincipal, keytab);
                        String previousCachedFilePath = principalEntity.getCachedKeytabPath();
                        String cachedKeytabFilePath = ((cachedKeytabFile == null) || !cachedKeytabFile.exists())
                            ? null
                            : cachedKeytabFile.getAbsolutePath();

                        principalEntity.setCachedKeytabPath(cachedKeytabFilePath);
                        kerberosPrincipalDAO.merge(principalEntity);

                        if(previousCachedFilePath != null) {
                          if(!new File(previousCachedFilePath).delete()) {
                            LOG.debug(String.format("Failed to remove orphaned cache file %s", previousCachedFilePath));
                          }
                        }
                      }
                    }
                  } catch (KerberosOperationException e) {
                    message = String.format("Failed to create keytab file for %s - %s", evaluatedPrincipal, e.getMessage());
                    actionLog.writeStdErr(message);
                    LOG.error(message, e);
                    commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
                  }
                }

                if (keytab != null) {
                  try {
                    if (operationHandler.createKeytabFile(keytab, destinationKeytabFile)) {
                      ensureAmbariOnlyAccess(destinationKeytabFile);

                      message = String.format("Successfully created keytab file for %s at %s", evaluatedPrincipal, destinationKeytabFile.getAbsolutePath());
                      LOG.debug(message);
                    } else {
                      message = String.format("Failed to create keytab file for %s at %s", evaluatedPrincipal, destinationKeytabFile.getAbsolutePath());
                      actionLog.writeStdErr(message);
                      LOG.error(message);
                      commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
                    }
                  } catch (KerberosOperationException e) {
                    message = String.format("Failed to create keytab file for %s - %s", evaluatedPrincipal, e.getMessage());
                    actionLog.writeStdErr(message);
                    LOG.error(message, e);
                    commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
                  }
                }
              }
            } else {
              message = String.format("Failed to create keytab file for %s, the container directory does not exist: %s",
                  evaluatedPrincipal, hostDirectory.getAbsolutePath());
              actionLog.writeStdErr(message);
              LOG.error(message);
              commandReport = createCommandReport(1, HostRoleStatus.FAILED, "{}", actionLog.getStdOut(), actionLog.getStdErr());
            }

            if(visitedPrincipalKeys == null) {
              visitedPrincipalKeys = new HashSet<String>();
              visitedIdentities.put(evaluatedPrincipal, visitedPrincipalKeys);
            }

            visitedPrincipalKeys.add(visitationKey);
          }
          else {
            LOG.debug(String.format("Skipping previously processed keytab for %s on host %s", evaluatedPrincipal, host));
          }
        }
      }
    }

    return commandReport;
  }

  /**
   * Cache a keytab given its relative principal name and the keytab data.
   * <p/>
   * The specified keytab is stored in a file in a location derived using the configured keytab
   * cache directory and the seeded hash of the principal name - this is to add a slight level
   * of obscurity so that it cannot be determined what keytab data is in the file based on its name.
   * The file is the set readable by only the Ambari server process owner.
   *
   * @param principal the principal name related to the keytab data
   * @param keytab    the keytab data to cache
   * @return a File pointing to the cached keytab file
   * @throws AmbariException if a failure occurs while creating the cache file containing the the keytab data
   */
  private File cacheKeytab(String principal, Keytab keytab) throws AmbariException {
    File cacheDirectory = configuration.getKerberosKeytabCacheDir();

    if (cacheDirectory == null) {
      String message = "The Kerberos keytab cache directory is not configured in the Ambari properties";
      LOG.error(message);
      throw new AmbariException(message);
    }

    if (!cacheDirectory.exists()) {
      // If the cache directory does not exist, create it and ensure only Ambari has access to it
      if (cacheDirectory.mkdirs()) {
        ensureAmbariOnlyAccess(cacheDirectory);

        if (!cacheDirectory.exists()) {
          String message = String.format("Failed to create the keytab cache directory %s",
              cacheDirectory.getAbsolutePath());
          LOG.error(message);
          throw new AmbariException(message);
        }
      }
    }

    File cachedKeytabFile = new File(cacheDirectory, DigestUtils.sha1Hex(principal + String.valueOf(System.currentTimeMillis())));

    try {
      keytab.write(cachedKeytabFile);
    } catch (IOException e) {
      String message = String.format("Failed to write the keytab for %s to the cache location (%s)",
          principal, cachedKeytabFile.getAbsolutePath());
      LOG.error(message, e);
      throw new AmbariException(message, e);
    }

    ensureAmbariOnlyAccess(cachedKeytabFile);

    return cachedKeytabFile;
  }

  /**
   * Ensures that the owner of the Ambari server process is the only local user account able to
   * read and write to the specified file or read, write to, and execute the specified directory.
   *
   * @param file the file or directory for which to modify access
   */
  protected void ensureAmbariOnlyAccess(File file) throws AmbariException {
    if (file.exists()) {
      if (!file.setReadable(false, false) || !file.setReadable(true, true)) {
        String message = String.format("Failed to set %s readable only by Ambari", file.getAbsolutePath());
        LOG.warn(message);
        throw new AmbariException(message);
      }

      if (!file.setWritable(false, false) || !file.setWritable(true, true)) {
        String message = String.format("Failed to set %s writable only by Ambari", file.getAbsolutePath());
        LOG.warn(message);
        throw new AmbariException(message);
      }

      if (file.isDirectory()) {
        if (!file.setExecutable(false, false) || !file.setExecutable(true, true)) {
          String message = String.format("Failed to set %s executable by Ambari", file.getAbsolutePath());
          LOG.warn(message);
          throw new AmbariException(message);
        }
      } else {
        if (!file.setExecutable(false, false)) {
          String message = String.format("Failed to set %s not executable", file.getAbsolutePath());
          LOG.warn(message);
          throw new AmbariException(message);
        }
      }
    }
  }
}
