/*
 * Copyright 2014 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apiman.manager.test.server;

import io.apiman.common.servlet.AuthenticationFilter;
import io.apiman.manager.api.security.impl.DefaultSecurityContextFilter;
import io.apiman.manager.test.util.ManagerTestUtils;
import io.apiman.manager.test.util.ManagerTestUtils.TestType;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.EnumSet;

import javax.naming.InitialContext;
import javax.naming.NameAlreadyBoundException;
import javax.naming.NamingException;
import javax.servlet.DispatcherType;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Credential;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.ImmutableSettings.Builder;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.plugins.server.servlet.ResteasyBootstrap;
import org.jboss.weld.environment.servlet.BeanManagerResourceBindingListener;
import org.jboss.weld.environment.servlet.Listener;
import org.overlord.commons.gwt.server.filters.SimpleCorsFilter;
import org.overlord.commons.i18n.server.filters.LocaleFilter;

/**
 * This class starts up an embedded Jetty test server so that integration tests
 * can be performed.
 *
 * @author eric.wittmann@redhat.com
 */
@SuppressWarnings("nls")
public class ManagerApiTestServer {

    public static final String ES_CLUSTER_NAME = "_apimantest";

    /*
     * The jetty server
     */
    private Server server;

    /*
     * DataSource created - only if using JPA
     */
    private BasicDataSource ds = null;

    /*
     * The elasticsearch node and client - only if using ES
     */
    private Node node;
    private TransportClient client;

    /**
     * Constructor.
     */
    public ManagerApiTestServer() {
    }
    
    /**
     * Start/run the server.
     */
    public void start() throws Exception {
        long startTime = System.currentTimeMillis();
        System.out.println("**** Starting Server (" + getClass().getSimpleName() + ")");
        preStart();

        ContextHandlerCollection handlers = new ContextHandlerCollection();
        addModulesToJetty(handlers);

        // Create the server.
        int serverPort = serverPort();
        server = new Server(serverPort);
        server.setHandler(handlers);
        server.start();
        long endTime = System.currentTimeMillis();
        System.out.println("******* Started in " + (endTime - startTime) + "ms");
    }
    
    /**
     * Stop the server.
     * @throws Exception
     */
    public void stop() throws Exception {
        server.stop();
        if (ds != null) {
            ds.close();
            InitialContext ctx = new InitialContext();
            ctx.unbind("java:comp/env/jdbc/ApiManagerDS");
        }
        if (node != null) {
            client.close();
            node.stop();
        }
    }

    /**
     * The server port.
     */
    public int serverPort() {
        return 7070;
    }

    /**
     * Stuff to do before the server is started.
     */
    protected void preStart() throws Exception {
        if (ManagerTestUtils.getTestType() == TestType.jpa) {
            System.setProperty("apiman.hibernate.hbm2ddl.auto", "create-drop");
            try {
                InitialContext ctx = new InitialContext();
                ensureCtx(ctx, "java:/comp/env");
                ensureCtx(ctx, "java:/comp/env/jdbc");
                String dbOutputPath = System.getProperty("apiman.test.h2-output-dir", null);
                if (dbOutputPath != null) {
                    ds = createFileDatasource(new File(dbOutputPath));
                } else {
                    ds = createInMemoryDatasource();
                }
                ctx.bind("java:/comp/env/jdbc/ApiManagerDS", ds);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        if (ManagerTestUtils.getTestType() == TestType.es) {
            System.out.println("Creating the ES node.");
            File esHome = new File("target/es");
            if (esHome.isDirectory()) {
                FileUtils.deleteDirectory(esHome);
            }
            Builder settings = NodeBuilder.nodeBuilder().settings();
            settings.put("path.home", esHome.getAbsolutePath());
            node = NodeBuilder.nodeBuilder().client(false).clusterName(ES_CLUSTER_NAME).data(true).local(false).settings(settings).build();
            System.out.println("Starting the ES node.");
            node.start();
            System.out.println("ES node was successfully started.");

            client = new TransportClient(ImmutableSettings.settingsBuilder().put("cluster.name", ES_CLUSTER_NAME)
                    .build());
            client.addTransportAddress(new InetSocketTransportAddress("localhost", 9300));
        }
    }

    /**
     * Ensure that the given name is bound to a context.
     * @param ctx
     * @param name
     * @throws NamingException 
     */
    private void ensureCtx(InitialContext ctx, String name) throws NamingException {
        try {
            ctx.bind(name, new InitialContext());
        } catch (NameAlreadyBoundException e) {
            // this is ok
        }
    }

    /**
     * Creates an in-memory datasource.
     * @throws SQLException
     */
    private static BasicDataSource createInMemoryDatasource() throws SQLException {
        System.setProperty("apiman.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(Driver.class.getName());
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
        Connection connection = ds.getConnection();
        connection.close();
        System.out.println("DataSource created and bound to JNDI.");
        return ds;
    }

    /**
     * Creates an h2 file based datasource.
     * @throws SQLException
     */
    private static BasicDataSource createFileDatasource(File outputDirectory) throws SQLException {
        System.setProperty("apiman.hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName(Driver.class.getName());
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setUrl("jdbc:h2:" + outputDirectory.toString() + "/apiman-manager-api;MVCC=true");
        Connection connection = ds.getConnection();
        connection.close();
        System.out.println("DataSource created and bound to JNDI.");
        return ds;
    }

    /**
     * Configure the web application(s).
     * @param handlers
     * @throws Exception
     */
    protected void addModulesToJetty(ContextHandlerCollection handlers) throws Exception {
        /* *************
         * APIMan DT API
         * ************* */
        ServletContextHandler apiManServer = new ServletContextHandler(ServletContextHandler.SESSIONS);
        apiManServer.setSecurityHandler(createSecurityHandler());
        apiManServer.setContextPath("/apiman");
        apiManServer.addEventListener(new Listener());
        apiManServer.addEventListener(new BeanManagerResourceBindingListener());
        apiManServer.addEventListener(new ResteasyBootstrap());
        apiManServer.addFilter(DatabaseSeedFilter.class, "/db-seeder", EnumSet.of(DispatcherType.REQUEST));
        apiManServer.addFilter(LocaleFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        apiManServer.addFilter(SimpleCorsFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        apiManServer.addFilter(AuthenticationFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        apiManServer.addFilter(DefaultSecurityContextFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        ServletHolder resteasyServlet = new ServletHolder(new HttpServletDispatcher());
        resteasyServlet.setInitParameter("javax.ws.rs.Application", TestManagerApiApplication.class.getName());
        apiManServer.addServlet(resteasyServlet, "/*");

        apiManServer.setInitParameter("resteasy.injector.factory", "org.jboss.resteasy.cdi.CdiInjectorFactory");
        apiManServer.setInitParameter("resteasy.scan", "true");
        apiManServer.setInitParameter("resteasy.servlet.mapping.prefix", "");

        // Add the web contexts to jetty
        handlers.addHandler(apiManServer);
        
        /* *************
         * Mock Gateway (to test publishing of Services from dt to rt)
         * ************* */
        ServletContextHandler mockGatewayServer = new ServletContextHandler(ServletContextHandler.SESSIONS);
        mockGatewayServer.setSecurityHandler(createSecurityHandler());
        mockGatewayServer.setContextPath("/mock-gateway");
        ServletHolder mockGatewayServlet = new ServletHolder(new MockGatewayServlet());
        mockGatewayServer.addServlet(mockGatewayServlet, "/*");

        // Add the web contexts to jetty
        handlers.addHandler(mockGatewayServer);
    }

    /**
     * Creates a basic auth security handler.
     */
    private SecurityHandler createSecurityHandler() {
        HashLoginService l = new HashLoginService();
        for (String [] userInfo : TestUsers.USERS) {
            String user = userInfo[0];
            String pwd = userInfo[1];
            String[] roles = new String[] { "apiuser" };
            if (user.startsWith("admin"))
                roles = new String[] { "apiuser", "apiadmin"};
            l.putUser(user, Credential.getCredential(pwd), roles);
        }
        l.setName("apimanrealm");

        ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setRealmName("apimanrealm");
        csh.setLoginService(l);

        return csh;
    }

}
