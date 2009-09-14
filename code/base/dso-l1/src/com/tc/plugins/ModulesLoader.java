/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright
 * notice. All rights reserved.
 */
package com.tc.plugins;

import org.apache.commons.io.IOUtils;
import org.apache.xmlbeans.XmlError;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlOptions;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import com.tc.bundles.EmbeddedOSGiEventHandler;
import com.tc.bundles.EmbeddedOSGiRuntime;
import com.tc.bundles.Repository;
import com.tc.bundles.Resolver;
import com.tc.bundles.ResolverUtils;
import com.tc.bundles.exception.BundleExceptionSummary;
import com.tc.config.schema.setup.ConfigurationSetupException;
import com.tc.logging.CustomerLogging;
import com.tc.logging.TCLogger;
import com.tc.logging.TCLogging;
import com.tc.management.beans.TIMByteProviderMBean;
import com.tc.object.config.ConfigLoader;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.MBeanSpec;
import com.tc.object.config.ModuleSpec;
import com.tc.object.config.OsgiServiceSpec;
import com.tc.object.config.SRASpec;
import com.tc.object.config.StandardDSOClientConfigHelper;
import com.tc.object.loaders.ClassProvider;
import com.tc.object.loaders.NamedClassLoader;
import com.tc.object.loaders.Namespace;
import com.tc.object.util.JarResourceLoader;
import com.tc.properties.TCProperties;
import com.tc.properties.TCPropertiesImpl;
import com.tc.util.Assert;
import com.tc.util.ProductInfo;
import com.tc.util.StringUtil;
import com.tc.util.VendorVmSignature;
import com.tc.util.VendorVmSignatureException;
import com.terracottatech.config.DsoApplication;
import com.terracottatech.config.Module;
import com.terracottatech.config.Modules;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

public class ModulesLoader {

  private static final Comparator SERVICE_COMPARATOR  = new Comparator() {

                                                        public int compare(final Object arg0, final Object arg1) {
                                                          ServiceReference s1 = (ServiceReference) arg0;
                                                          ServiceReference s2 = (ServiceReference) arg1;

                                                          Integer r1 = (Integer) s1
                                                              .getProperty(Constants.SERVICE_RANKING);
                                                          Integer r2 = (Integer) s2
                                                              .getProperty(Constants.SERVICE_RANKING);

                                                          if (r1 == null) r1 = OsgiServiceSpec.NORMAL_RANK;
                                                          if (r2 == null) r2 = OsgiServiceSpec.NORMAL_RANK;

                                                          return r2.compareTo(r1);
                                                        }

                                                      };

  private static final TCLogger   logger              = TCLogging.getLogger(ModulesLoader.class);
  private static final TCLogger   consoleLogger       = CustomerLogging.getConsoleLogger();

  private static final Object     lock                = new Object();
  private static final String     NEWLINE             = System.getProperty("line.separator", "\n");

  public static final String      TC_BOOTJAR_CREATION = "tc.bootjar.creation";

  private ModulesLoader() {
    // cannot be instantiated
  }

  public static void initModules(final DSOClientConfigHelper configHelper, final ClassProvider classProvider,
                                 final boolean forBootJar) throws Exception {
    initModules(configHelper, classProvider, forBootJar, Collections.EMPTY_LIST);
  }

  public static void initModules(final DSOClientConfigHelper configHelper, final ClassProvider classProvider,
                                 final boolean forBootJar, Collection<Repository> addlRepos) throws Exception {
    if (forBootJar) {
      System.setProperty(TC_BOOTJAR_CREATION, Boolean.TRUE.toString());
    }
    EmbeddedOSGiRuntime osgiRuntime = null;
    synchronized (lock) {
      final Modules modules = configHelper.getModulesForInitialization();
      if (modules == null) {
        consoleLogger.warn("Modules configuration might not have been properly initialized.");
        return;
      }

      try {
        osgiRuntime = EmbeddedOSGiRuntime.Factory.createOSGiRuntime(modules);
        initModules(osgiRuntime, configHelper, classProvider, modules.getModuleArray(), forBootJar, addlRepos);
        if (!forBootJar) {
          getModulesCustomApplicatorSpecs(osgiRuntime, configHelper);
          getModulesMBeanSpecs(osgiRuntime, configHelper);
          getModulesSRASpecs(osgiRuntime, configHelper);
        }
      } catch (BundleException e) {
        if (e instanceof BundleExceptionSummary) {
          String msg = ((BundleExceptionSummary) e).getSummary();
          e = new BundleException(msg);
        }
        throw e;
      } finally {
        if (forBootJar) {
          System.getProperties().remove(TC_BOOTJAR_CREATION);
          shutdown(osgiRuntime);
        }
      }
    }
  }

  private static void shutdown(final EmbeddedOSGiRuntime osgiRuntime) {
    if (osgiRuntime != null) {
      osgiRuntime.shutdown();
    }
  }

  static void initModules(final EmbeddedOSGiRuntime osgiRuntime, final DSOClientConfigHelper configHelper,
                          final ClassProvider classProvider, final Module[] modules, final boolean forBootJar)
      throws BundleException {
    initModules(osgiRuntime, configHelper, classProvider, modules, forBootJar, Collections.EMPTY_LIST);
  }

  static void initModules(final EmbeddedOSGiRuntime osgiRuntime, final DSOClientConfigHelper configHelper,
                          final ClassProvider classProvider, final Module[] modules, final boolean forBootJar,
                          Collection<Repository> addlRepos) throws BundleException {

    if (configHelper instanceof StandardDSOClientConfigHelper) {
      final Dictionary serviceProps = new Hashtable();
      serviceProps.put(Constants.SERVICE_VENDOR, "Terracotta, Inc.");
      serviceProps.put(Constants.SERVICE_DESCRIPTION, "Main point of entry for programmatic access to"
                                                      + " the Terracotta bytecode instrumentation");
      osgiRuntime.registerService(StandardDSOClientConfigHelper.class.getName(), configHelper, serviceProps);
    }

    final List moduleList = new ArrayList();
    moduleList.addAll(getAdditionalModules());
    moduleList.addAll(Arrays.asList(modules));

    final Module[] allModules = (Module[]) moduleList.toArray(new Module[moduleList.size()]);

    final URL[] osgiRepositories = osgiRuntime.getRepositories();
    final ProductInfo info = ProductInfo.getInstance();
    final Resolver resolver = new Resolver(ResolverUtils.urlsToStrings(osgiRepositories), true, info
        .mavenArtifactsVersion(), info.apiVersion(), addlRepos);
    final URL[] locations = resolver.resolve(allModules);

    final Map<Bundle, URL> bundleURLs = osgiRuntime.installBundles(locations);
    configHelper.setBundleURLs(bundleURLs);

    EmbeddedOSGiEventHandler handler = new EmbeddedOSGiEventHandler() {
      public void callback(final Object payload) throws BundleException {
        Assert.assertTrue(payload instanceof Bundle);
        Bundle bundle = (Bundle) payload;
        if (bundle != null) {
          if (!forBootJar) {
            registerClassLoader(configHelper, classProvider, bundle);

            Dictionary headers = bundle.getHeaders();
            if (headers.get("Presentation-Factory") != null) {
              logger.info("Installing TIMByteProvider for bundle '" + bundle.getSymbolicName() + "'");
              installTIMByteProvider(bundle);
            }
          }
          printModuleBuildInfo(bundle);
          loadConfiguration(configHelper, bundle, bundleURLs.get(bundle));
        }
      }
    };

    osgiRuntime.startBundles(locations, handler);

  }

  private static void installTIMByteProvider(final Bundle bundle) {
    try {
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
      Dictionary headers = bundle.getHeaders();
      String description = (String) headers.get("Bundle-Description");
      String version = (String) headers.get("Bundle-Version");
      String feature = bundle.getSymbolicName() + "-" + version;
      String prefix;
      if (description != null) {
        prefix = "org.terracotta:type=Loader,name=" + description + ",feature=";
      } else {
        prefix = "org.terracotta:type=Loader,feature=";
      }
      ObjectName loader = new ObjectName(prefix + feature);
      if (!mbs.isRegistered(loader)) {
        mbs.registerMBean(new StandardMBean(new TIMByteProvider(bundle.getLocation()), TIMByteProviderMBean.class),
                          loader);
      }
    } catch (Exception e) {
      logger.warn("Unable to install TIMByteProvider for bundle '" + bundle.getSymbolicName() + "'", e);
    }
  }

  protected static void printModuleBuildInfo(Bundle bundle) {
    Dictionary headers = bundle.getHeaders();
    StringBuilder sb = new StringBuilder("BuildInfo for module: " + bundle.getSymbolicName()
                                         + StringUtil.LINE_SEPARATOR);
    boolean found = false;
    for (Enumeration keys = headers.keys(); keys.hasMoreElements();) {
      String key = (String) keys.nextElement();
      if (key.indexOf("BuildInfo") > -1) {
        sb.append("  " + key + ": " + headers.get(key)).append(StringUtil.LINE_SEPARATOR);
        found = true;
      }
    }
    if (found) {
      logger.info(sb.toString());
    }
  }

  private static List getAdditionalModules() {
    final List modules = new ArrayList();
    final TCProperties modulesProps = TCPropertiesImpl.getProperties().getPropertiesFor("l1.modules");
    final String additionalModuleList = modulesProps != null ? modulesProps.getProperty("additional", true) : null;

    if (additionalModuleList != null) {
      final String[] additionalModules = additionalModuleList.split(";");
      Pattern pattern = Pattern.compile("(.+?)-([0-9\\.]+)-([0-9\\.\\-]+)");
      for (String additionalModule : additionalModules) {
        if (additionalModule.length() == 0) continue;

        final Matcher matcher = pattern.matcher(additionalModule);
        if (!matcher.find() || matcher.groupCount() < 3) {
          logger.error("Invalid bundle-jar filename " + additionalModule + "; filenames need to match the pattern: "
                       + pattern.toString());
          continue;
        }

        String component = matcher.group(1);
        final String componentVersion = matcher.group(2);
        final String moduleVersion = matcher.group(3).replaceFirst("\\.$", "");

        final Module module = Module.Factory.newInstance();
        String groupId = module.getGroupId(); // rely on the constant defined in the schema for the default groupId
        final int n = component.lastIndexOf('.');
        if (n > 0) {
          groupId = component.substring(0, n);
          component = component.substring(n + 1);
          module.setGroupId(groupId);
        }

        module.setName(component + "-" + componentVersion);
        module.setVersion(moduleVersion);
        modules.add(module);
      }
    }

    return modules;
  }

  private static void registerClassLoader(final DSOClientConfigHelper config, final ClassProvider classProvider,
                                          final Bundle bundle) throws BundleException {
    if (config.hasBootJar()) {
      NamedClassLoader ncl = getClassLoader(bundle);
      String loaderName = Namespace.createLoaderName(Namespace.MODULES_NAMESPACE, ncl.toString());
      ncl.__tc_setClassLoaderName(loaderName);
      String appGroup = config.getAppGroup(loaderName, null);
      classProvider.registerNamedLoader(ncl, appGroup);
    }
  }

  private static NamedClassLoader getClassLoader(final Bundle bundle) throws BundleException {
    try {
      Method m = bundle.getClass().getDeclaredMethod("getClassLoader", new Class[0]);
      m.setAccessible(true);
      ClassLoader classLoader = (ClassLoader) m.invoke(bundle, new Object[0]);
      return (NamedClassLoader) classLoader;
    } catch (Exception e) {
      throw new BundleException("Unable to get classloader for bundle.", e);
    }
  }

  private static void getModulesCustomApplicatorSpecs(final EmbeddedOSGiRuntime osgiRuntime,
                                                      final DSOClientConfigHelper configHelper)
      throws InvalidSyntaxException {
    ServiceReference[] serviceReferences = osgiRuntime.getAllServiceReferences(ModuleSpec.class.getName(), null);
    if (serviceReferences != null && serviceReferences.length > 0) {
      Arrays.sort(serviceReferences, SERVICE_COMPARATOR);
    }

    if (serviceReferences == null) { return; }
    ModuleSpec[] modulesSpecs = new ModuleSpec[serviceReferences.length];
    for (int i = 0; i < serviceReferences.length; i++) {
      modulesSpecs[i] = (ModuleSpec) osgiRuntime.getService(serviceReferences[i]);
      osgiRuntime.ungetService(serviceReferences[i]);
    }
    configHelper.setModuleSpecs(modulesSpecs);
  }

  private static void getModulesMBeanSpecs(final EmbeddedOSGiRuntime osgiRuntime,
                                           final DSOClientConfigHelper configHelper) throws InvalidSyntaxException {
    ServiceReference[] serviceReferences = osgiRuntime.getAllServiceReferences(MBeanSpec.class.getName(), null);
    if (serviceReferences != null && serviceReferences.length > 0) {
      Arrays.sort(serviceReferences, SERVICE_COMPARATOR);
    }

    if (serviceReferences == null) { return; }
    MBeanSpec[] mbeanSpecs = new MBeanSpec[serviceReferences.length];
    for (int i = 0; i < serviceReferences.length; i++) {
      mbeanSpecs[i] = (MBeanSpec) osgiRuntime.getService(serviceReferences[i]);
      osgiRuntime.ungetService(serviceReferences[i]);
    }
    configHelper.setMBeanSpecs(mbeanSpecs);
  }

  private static void getModulesSRASpecs(final EmbeddedOSGiRuntime osgiRuntime, final DSOClientConfigHelper configHelper)
      throws InvalidSyntaxException {
    ServiceReference[] serviceReferences = osgiRuntime.getAllServiceReferences(SRASpec.class.getName(), null);
    if (serviceReferences != null && serviceReferences.length > 0) {
      Arrays.sort(serviceReferences, SERVICE_COMPARATOR);
    }

    if (serviceReferences == null) { return; }
    SRASpec[] sraSpecs = new SRASpec[serviceReferences.length];
    for (int i = 0; i < serviceReferences.length; i++) {
      sraSpecs[i] = (SRASpec) osgiRuntime.getService(serviceReferences[i]);
      osgiRuntime.ungetService(serviceReferences[i]);
    }
    configHelper.setSRASpecs(sraSpecs);
  }

  /**
   * Extract the list of xml-fragment files that a config bundle should use for instrumentation.
   */
  public static String[] getConfigPath(final Bundle bundle) throws BundleException {
    final VendorVmSignature vmsig;
    try {
      vmsig = new VendorVmSignature();
    } catch (VendorVmSignatureException e) {
      throw new BundleException(e.getMessage());
    }
    final String TERRACOTTA_CONFIGURATION = "Terracotta-Configuration";
    final String TERRACOTTA_CONFIGURATION_FOR_VM = TERRACOTTA_CONFIGURATION + VendorVmSignature.SIGNATURE_SEPARATOR
                                                   + vmsig.getSignature();

    String path = (String) bundle.getHeaders().get(TERRACOTTA_CONFIGURATION_FOR_VM);
    if (path == null) {
      path = (String) bundle.getHeaders().get(TERRACOTTA_CONFIGURATION);
      if (path == null) path = "terracotta.xml";
    }

    final String EXTENSION = ".xml";
    final String[] paths = path.split(",");
    for (int i = 0; i < paths.length; i++) {
      paths[i] = paths[i].trim();
      if (!paths[i].endsWith(EXTENSION)) paths[i] = paths[i].concat(EXTENSION);
    }

    return paths;
  }

  private static void loadConfiguration(final DSOClientConfigHelper configHelper, Bundle bundle, final URL url)
      throws BundleException {
    // attempt to load all of the config fragments found in the config-bundle
    final String[] paths = getConfigPath(bundle);
    for (final String configPath : paths) {
      final InputStream is;
      try {
        is = JarResourceLoader.getJarResource(url, configPath);
      } catch (IOException ioe) {
        throw new BundleException("Unable to extract " + configPath + " from URL: " + url, ioe);
      }

      if (is == null) {
        continue;
      }

      // otherwise, merge it with the current configuration
      try {
        final DsoApplication application = DsoApplication.Factory.parse(is);
        if (application != null) {
          final ConfigLoader loader = new ConfigLoader(configHelper, logger);
          logConfig(application, bundle, configPath);
          validateBundleFragment(application);
          loader.loadDsoConfig(application);
        }
        is.close();
      } catch (IOException ioe) {
        String msg = "Error reading configuration from bundle: " + bundle.getSymbolicName() + " located at "
                     + bundle.getLocation();
        consoleLogger.warn(msg, ioe);
        logger.warn(msg, ioe);
        throw new BundleException(msg, ioe);
      } catch (XmlException xmle) {
        String msg = "Error parsing configuration from bundle: " + bundle.getSymbolicName() + " located at "
                     + bundle.getLocation();
        consoleLogger.warn(msg, xmle);
        logger.warn(msg, xmle);
        throw new BundleException(msg, xmle);
      } catch (ConfigurationSetupException cse) {
        String msg = "Invalid configuration in bundle: " + bundle.getSymbolicName() + " located at "
                     + bundle.getLocation() + ": " + cse.getMessage();
        consoleLogger.warn(msg, cse);
        logger.warn(msg, cse);
        throw new BundleException(msg, cse);
      } finally {
        IOUtils.closeQuietly(is);
      }
    }
  }

  /**
   * DEV-1238 and DEV-2205
   * 
   * @author hhuynh
   */
  private static void validateBundleFragment(final DsoApplication application) throws XmlException {
    // Create an XmlOptions instance and set the error listener.
    XmlOptions validateOptions = new XmlOptions();
    ArrayList errorList = new ArrayList();
    validateOptions.setErrorListener(errorList);

    // Validate the XML.
    boolean isValid = application.validate(validateOptions);

    // If the XML isn't valid, loop through the listener's contents,
    // printing contained messages.
    if (!isValid) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < errorList.size(); i++) {
        XmlError error = (XmlError) errorList.get(i);
        sb.append(NEWLINE);
        sb.append("Parse error: " + error.getMessage());
      }
      sb.append(NEWLINE);
      throw new XmlException(sb.toString());
    }

  }

  private static void logConfig(final DsoApplication application, final Bundle bundle, final String configPath) {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    BufferedOutputStream buf = new BufferedOutputStream(bas);
    try {
      application.save(buf);
      buf.close();
      logger.info("Config loaded from module: " + bundle.getSymbolicName() + " (" + configPath + ")" + NEWLINE
                  + bas.toString());
    } catch (IOException e) {
      logger.warn("Unable to generate a log entry to for the module's config info.");
    }
  }
}
