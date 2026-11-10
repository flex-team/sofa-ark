/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.ark.springboot.web;

import com.alipay.sofa.ark.api.ArkClient;
import com.alipay.sofa.ark.spi.model.Biz;
import com.alipay.sofa.ark.spi.service.ArkInject;
import com.alipay.sofa.ark.spi.service.biz.BizManagerService;
import com.alipay.sofa.ark.spi.web.EmbeddedServerService;
import jakarta.servlet.ServletContainerInitializer;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappClassLoaderBase;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.scan.StandardJarScanFilter;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.ContextPath;
import org.springframework.boot.web.server.servlet.DocumentRoot;
import org.springframework.boot.web.server.servlet.Jsp;
import org.springframework.boot.web.server.servlet.ServletContextInitializers;
import org.springframework.boot.web.server.servlet.ServletWebServerSettings;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.alipay.sofa.ark.spi.constant.Constants.ROOT_WEB_CONTEXT_PATH;

public class ArkTomcatServletWebServerFactory extends TomcatServletWebServerFactory {

    private static final Log      LOGGER                  = LogFactory
                                                              .getLog(ArkTomcatServletWebServerFactory.class);

    private static final Charset  DEFAULT_CHARSET         = StandardCharsets.UTF_8;

    private static final String   ARK_WEBAPP_CLASS_LOADER = "com.alipay.sofa.ark.web.embed.tomcat.ArkTomcatEmbeddedWebappClassLoader";

    private final Object          lock                    = new Object();

    @ArkInject
    private EmbeddedServerService embeddedServerService;

    @ArkInject
    private BizManagerService     bizManagerService;

    @Override
    public WebServer getWebServer(ServletContextInitializer... initializers) {
        if (embeddedServerService == null && ArkClient.getInjectionService() != null) {
            ArkClient.getInjectionService().inject(this);
        }
        applyBizContextPath();
        if (embeddedServerService == null) {
            return super.getWebServer(initializers);
        }
        if (embeddedServerService.getEmbedServer(getPort()) == null) {
            synchronized (lock) {
                if (embeddedServerService.getEmbedServer(getPort()) == null) {
                    embeddedServerService.putEmbedServer(getPort(), createTomcat());
                }
            }
        }
        Tomcat embedTomcat = (Tomcat) embeddedServerService.getEmbedServer(getPort());
        prepareContext(embedTomcat.getHost(), initializers);
        return getWebServer(embedTomcat);
    }

    @Override
    public String getContextPath() {
        String contextPath = getSettings().getContextPath().toString();
        if (bizManagerService == null) {
            return contextPath;
        }
        Biz biz = bizManagerService.getBizByClassLoader(Thread.currentThread()
            .getContextClassLoader());
        if (StringUtils.hasLength(contextPath)) {
            return contextPath;
        } else if (biz != null) {
            if (!StringUtils.hasLength(biz.getWebContextPath())) {
                return ROOT_WEB_CONTEXT_PATH;
            }
            return biz.getWebContextPath();
        } else {
            return ROOT_WEB_CONTEXT_PATH;
        }
    }

    private void applyBizContextPath() {
        String resolved = getContextPath();
        if (!StringUtils.hasLength(resolved) || ROOT_WEB_CONTEXT_PATH.equals(resolved)) {
            return;
        }
        String normalized = resolved.startsWith("/") ? resolved : "/" + resolved;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.equals(getSettings().getContextPath().toString())) {
            getSettings().setContextPath(ContextPath.of(normalized));
        }
    }

    @Override
    protected void postProcessContext(Context context) {
        WebappClassLoaderBase arkLoader = createArkWebappClassLoader(context.getParentClassLoader());
        if (arkLoader != null) {
            ((WebappLoader) context.getLoader()).setLoaderInstance(arkLoader);
        }
    }

    private WebappClassLoaderBase createArkWebappClassLoader(ClassLoader parent) {
        ClassLoader[] candidates = { parent, WebappLoader.class.getClassLoader() };
        for (ClassLoader candidate : candidates) {
            try {
                Class<?> clazz = ClassUtils.forName(ARK_WEBAPP_CLASS_LOADER, candidate);
                if (WebappClassLoaderBase.class.isAssignableFrom(clazz)) {
                    return (WebappClassLoaderBase) clazz.getConstructor(ClassLoader.class)
                        .newInstance(parent);
                }
            } catch (Throwable ex) {
                LOGGER.debug("Cannot create " + ARK_WEBAPP_CLASS_LOADER + " via " + candidate, ex);
            }
        }
        LOGGER.warn(ARK_WEBAPP_CLASS_LOADER + " not available, using default webapp class loader");
        return null;
    }

    @Override
    protected void prepareContext(Host host, ServletContextInitializer[] initializers) {
        if (host.getState() == LifecycleState.NEW) {
            super.prepareContext(host, initializers);
            return;
        }
        ServletWebServerSettings settings = getSettings();
        DocumentRoot documentRootResolver = new DocumentRoot(LOGGER);
        documentRootResolver.setDirectory(settings.getDocumentRoot());
        File documentRoot = documentRootResolver.getValidDirectory();
        StandardContext context = new StandardContext();
        if (documentRoot != null) {
            context.setResources(new StandardRoot(context));
        }
        context.setName(getContextPath());
        context.setDisplayName(settings.getDisplayName());
        context.setPath(getContextPath());
        File docBase = (documentRoot != null) ? documentRoot : createTempDir("tomcat-docbase");
        context.setDocBase(docBase.getAbsolutePath());
        context.addLifecycleListener(new Tomcat.FixContextListener());
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        context.setParentClassLoader(parent);
        resetDefaultLocaleMapping(context);
        addLocaleMappings(context, settings);
        context.setUseRelativeRedirects(false);
        configureTldSkipPatterns(context);
        WebappLoader loader = new WebappLoader();
        WebappClassLoaderBase arkLoader = createArkWebappClassLoader(parent);
        if (arkLoader != null) {
            loader.setLoaderInstance(arkLoader);
        }
        loader.setDelegate(true);
        context.setLoader(loader);
        if (settings.isRegisterDefaultServlet()) {
            addDefaultServlet(context);
        }
        if (shouldRegisterJspServlet(settings)) {
            addJspServlet(context, settings.getJsp());
            addJasperInitializer(context);
        }
        context.addLifecycleListener(new StaticResourceConfigurer(context, settings));
        ServletContextInitializers initializersToUse = ServletContextInitializers.from(settings,
            initializers);
        context.setParent(host);
        configureContext(context, initializersToUse);
        host.addChild(context);
    }

    private boolean shouldRegisterJspServlet(ServletWebServerSettings settings) {
        Jsp jsp = settings.getJsp();
        return jsp != null && jsp.getRegistered()
               && ClassUtils.isPresent(jsp.getClassName(), getClass().getClassLoader());
    }

    private void resetDefaultLocaleMapping(StandardContext context) {
        context.addLocaleEncodingMappingParameter(Locale.ENGLISH.toString(),
            DEFAULT_CHARSET.displayName());
        context.addLocaleEncodingMappingParameter(Locale.FRENCH.toString(),
            DEFAULT_CHARSET.displayName());
    }

    private void addLocaleMappings(StandardContext context, ServletWebServerSettings settings) {
        for (Map.Entry<Locale, Charset> entry : settings.getLocaleCharsetMappings().entrySet()) {
            context.addLocaleEncodingMappingParameter(entry.getKey().toString(), entry.getValue()
                .toString());
        }
    }

    private void configureTldSkipPatterns(StandardContext context) {
        StandardJarScanFilter filter = new StandardJarScanFilter();
        filter.setTldSkip(StringUtils.collectionToCommaDelimitedString(getTldSkipPatterns()));
        context.getJarScanner().setJarScanFilter(filter);
    }

    private void addDefaultServlet(Context context) {
        Wrapper defaultServlet = context.createWrapper();
        defaultServlet.setName("default");
        defaultServlet.setServletClass("org.apache.catalina.servlets.DefaultServlet");
        defaultServlet.addInitParameter("debug", "0");
        defaultServlet.addInitParameter("listings", "false");
        defaultServlet.setLoadOnStartup(1);
        defaultServlet.setOverridable(true);
        context.addChild(defaultServlet);
        context.addServletMappingDecoded("/", "default");
    }

    private void addJspServlet(Context context, Jsp jsp) {
        Wrapper jspServlet = context.createWrapper();
        jspServlet.setName("jsp");
        jspServlet.setServletClass(jsp.getClassName());
        jspServlet.addInitParameter("fork", "false");
        for (Map.Entry<String, String> entry : jsp.getInitParameters().entrySet()) {
            jspServlet.addInitParameter(entry.getKey(), entry.getValue());
        }
        jspServlet.setLoadOnStartup(3);
        context.addChild(jspServlet);
        context.addServletMappingDecoded("*.jsp", "jsp");
        context.addServletMappingDecoded("*.jspx", "jsp");
    }

    private void addJasperInitializer(StandardContext context) {
        try {
            ServletContainerInitializer initializer = (ServletContainerInitializer) ClassUtils
                .forName("org.apache.jasper.servlet.JasperInitializer", null)
                .getDeclaredConstructor().newInstance();
            context.addServletContainerInitializer(initializer, null);
        } catch (Exception ex) {
            LOGGER.debug("JasperInitializer not available", ex);
        }
    }

    final class StaticResourceConfigurer implements LifecycleListener {

        private final Context                  context;

        private final ServletWebServerSettings settings;

        private StaticResourceConfigurer(Context context, ServletWebServerSettings settings) {
            this.context = context;
            this.settings = settings;
        }

        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (event.getType().equals(Lifecycle.CONFIGURE_START_EVENT)) {
                addResourceJars(settings.getStaticResourceUrls());
            }
        }

        private void addResourceJars(List<URL> resourceJarUrls) {
            for (URL url : resourceJarUrls) {
                String path = url.getPath();
                if (path.endsWith(".jar") || path.endsWith(".jar!/")) {
                    String jar = url.toString();
                    if (!jar.startsWith("jar:")) {
                        jar = "jar:" + jar + "!/";
                    }
                    addResourceSet(jar);
                } else {
                    addResourceSet(url.toString());
                }
            }
        }

        private void addResourceSet(String resource) {
            try {
                if (isInsideNestedJar(resource)) {
                    resource = resource.substring(0, resource.length() - 2);
                }
                URL url = new URL(resource);
                this.context.getResources().createWebResourceSet(
                    WebResourceRoot.ResourceSetType.RESOURCE_JAR, "/", url, "/META-INF/resources");
            } catch (Exception ex) {
                LOGGER.debug("Ignoring resource " + resource, ex);
            }
        }

        private boolean isInsideNestedJar(String dir) {
            return dir.indexOf("!/") < dir.lastIndexOf("!/");
        }
    }

    protected WebServer getWebServer(Tomcat tomcat) {
        return new ArkTomcatWebServer(tomcat, getPort() >= 0, tomcat);
    }
}
