package net.md_5.bungee;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.Format;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import lombok.Setter;
import lombok.Synchronized;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ReconnectHandler;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ConfigurationAdapter;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.command.CommandBungee;
import net.md_5.bungee.command.CommandEnd;
import net.md_5.bungee.command.CommandIP;
import net.md_5.bungee.command.CommandPerms;
import net.md_5.bungee.command.CommandReload;
import net.md_5.bungee.command.ConsoleCommandCompleter;
import net.md_5.bungee.command.ConsoleCommandSender;
import net.md_5.bungee.compress.CompressFactory;
import net.md_5.bungee.conf.Configuration;
import net.md_5.bungee.conf.YamlConfig;
import net.md_5.bungee.forge.ForgeConstants;
import net.md_5.bungee.jni.NativeCode;
import net.md_5.bungee.log.BungeeLogger;
import net.md_5.bungee.log.LoggingForwardHandler;
import net.md_5.bungee.log.LoggingOutputStream;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.channel.BungeeChannelInitializer;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.query.RemoteQuery;
import net.md_5.bungee.scheduler.BungeeScheduler;
import net.md_5.bungee.util.CaseInsensitiveMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.jul.JDK14LoggerFactory;

/**
 * Main BungeeCord proxy class.
 */
public class BungeeCord extends ProxyServer
{

    /**
     * Current operation state.
     */
    public volatile boolean isRunning;
    /**
     * Configuration.
     */
    @Getter
    public final Configuration config = new Configuration();
    /**
     * Localization formats.
     */
    private Map<String, Format> messageFormats;
    public EventLoopGroup eventLoops;
    /**
     * locations.yml save thread.
     */
    private final Timer saveThread = new Timer( "Reconnect Saver" );
    private final Timer metricsThread = new Timer( "Metrics Thread" );
    /**
     * Server socket listener.
     */
    private final Collection<Channel> listeners = new HashSet<>();
    /**
     * Fully qualified connections.
     */
    private final Map<String, UserConnection> connections = new CaseInsensitiveMap<>();
    // Used to help with packet rewriting
    private final Map<UUID, UserConnection> connectionsByOfflineUUID = new HashMap<>();
    private final Map<UUID, UserConnection> connectionsByUUID = new HashMap<>();
    private final ReadWriteLock connectionLock = new ReentrantReadWriteLock();
    /**
     * Lock to protect the shutdown process from being triggered simultaneously
     * from multiple sources.
     */
    private final ReentrantLock shutdownLock = new ReentrantLock();
    /**
     * Plugin manager.
     */
    @Getter
    public final PluginManager pluginManager;
    @Getter
    @Setter
    private ReconnectHandler reconnectHandler;
    @Getter
    @Setter
    private ConfigurationAdapter configurationAdapter = new YamlConfig();
    private final Collection<String> pluginChannels = new HashSet<>();
    @Getter
    private final File pluginsFolder = new File( "plugins" );
    @Getter
    private final BungeeScheduler scheduler = new BungeeScheduler();
    @Getter
    private final LineReader consoleReader;
    @Getter
    private final Logger logger;
    @Getter
    private ConnectionThrottle connectionThrottle;

    {
        // TODO: Proper fallback when we interface the manager
        registerChannel( PluginMessage.BUNGEE_CHANNEL_LEGACY );
    }

    public static BungeeCord getInstance()
    {
        return (BungeeCord) ProxyServer.getInstance();
    }

    private final Unsafe unsafe = new Unsafe()
    {
        @Getter
        @Setter
        private BungeeChannelInitializer frontendChannelInitializer;

        @Getter
        @Setter
        private BungeeChannelInitializer backendChannelInitializer;

        @Getter
        @Setter
        private BungeeChannelInitializer serverInfoChannelInitializer;
    };

    public BungeeCord() throws IOException
    {
        // Java uses ! to indicate a resource inside of a jar/zip/other container. Running Bungee from within a directory that has a ! will cause this to muck up.
        Preconditions.checkState( new File( "." ).getAbsolutePath().indexOf( '!' ) == -1, "Cannot use BungeeCord in directory with ! in path." );

        reloadMessages();

        // This is a workaround for quite possibly the weirdest bug I have ever encountered in my life!
        // When jansi attempts to extract its natives, by default it tries to extract a specific version,
        // using the loading class's implementation version. Normally this works completely fine,
        // however when on Windows certain characters such as - and : can trigger special behaviour.
        // Furthermore this behaviour only occurs in specific combinations due to the parsing done by jansi.
        // For example test-test works fine, but test-test-test does not! In order to avoid this all together but
        // still keep our versions the same as they were, we set the override property to the essentially garbage version
        // BungeeCord. This version is only used when extracting the libraries to their temp folder.
        System.setProperty( "library.jansi.version", "BungeeCord" );

        Terminal terminal = TerminalBuilder.builder().build();
        consoleReader = LineReaderBuilder.builder().terminal( terminal )
                .option( LineReader.Option.DISABLE_EVENT_EXPANSION, true )
                .completer( new ConsoleCommandCompleter( this ) )
                .build();

        logger = new BungeeLogger( "BungeeCord", "proxy.log", consoleReader );
        JDK14LoggerFactory.LOGGER = logger;

        // Before we can set the Err and Out streams to our LoggingOutputStream we also have to remove
        // the default ConsoleHandler from the root logger, which writes to the err stream.
        // But we still want to log these records, so we add our own handler which forwards the LogRecord to the BungeeLogger.
        // This way we skip the err stream and the problem of only getting a string without context, and can handle the LogRecord itself.
        // Thus improving the default bahavior for projects that log on other Logger instances not created by BungeeCord.
        Logger rootLogger = Logger.getLogger( "" );
        for ( Handler handler : rootLogger.getHandlers() )
        {
            rootLogger.removeHandler( handler );
        }
        rootLogger.addHandler( new LoggingForwardHandler( logger ) );

        // We want everything that reaches these output streams to be handled by our logger
        // since it applies a nice looking format and also writes to the logfile.
        System.setErr( new PrintStream( new LoggingOutputStream( logger, Level.SEVERE ), true ) );
        System.setOut( new PrintStream( new LoggingOutputStream( logger, Level.INFO ), true ) );

        pluginManager = new PluginManager( this );
        getPluginManager().registerCommand( null, new CommandReload() );
        getPluginManager().registerCommand( null, new CommandEnd() );
        getPluginManager().registerCommand( null, new CommandIP() );
        getPluginManager().registerCommand( null, new CommandBungee() );
        getPluginManager().registerCommand( null, new CommandPerms() );

        if ( !Boolean.getBoolean( "net.md_5.bungee.native.disable" ) )
        {
            if ( !NativeCode.hasDirectBuffers() )
            {
                logger.warning( "Memory addresses are not available in direct buffers" );
            }

            if ( EncryptionUtil.nativeFactory.load() )
            {
                logger.info( "Using mbed TLS based native cipher." );
            } else
            {
                logger.info( "Using standard Java JCE cipher." );
            }
            if ( CompressFactory.zlib.load() )
            {
                logger.info( "Using zlib based native compressor." );
            } else
            {
                logger.info( "Using standard Java compressor." );
            }
        }
    }

    /**
     * Start this proxy instance by loading the configuration, plugins and
     * starting the connect thread.
     *
     * @throws Exception any critical errors encountered
     */
    public void start() throws Exception
    {
        System.setProperty( "io.netty.selectorAutoRebuildThreshold", "0" ); // Seems to cause Bungee to stop accepting connections
        if ( System.getProperty( "io.netty.leakDetectionLevel" ) == null && System.getProperty( "io.netty.leakDetection.level" ) == null )
        {
            ResourceLeakDetector.setLevel( ResourceLeakDetector.Level.DISABLED ); // Eats performance
        }

        // https://github.com/netty/netty/wiki/Netty-4.2-Migration-Guide
        // The adaptive allocator, the new default allocator since Netty 4.2, has some memory issues.
        // Setting it globally also ensures that any plugins would also use the pooled allocator.
        if ( System.getProperty( "io.netty.allocator.type" ) == null )
        {
            System.setProperty( "io.netty.allocator.type", "pooled" );
        }

        eventLoops = PipelineUtils.newEventLoopGroup( 0, new ThreadFactoryBuilder().setNameFormat( "Netty IO Thread #%1$d" ).build() );

        pluginsFolder.mkdir();
        pluginManager.detectPlugins( pluginsFolder );

        pluginManager.loadPlugins();
        config.load();

        if ( config.isForgeSupport() )
        {
            registerChannel( ForgeConstants.FML_TAG );
            registerChannel( ForgeConstants.FML_HANDSHAKE_TAG );
            registerChannel( ForgeConstants.FORGE_REGISTER );

            getLogger().warning( "MinecraftForge support is currently unmaintained and may have unresolved issues. Please use at your own risk." );
        }

        isRunning = true;

        pluginManager.enablePlugins();

        if ( config.getThrottle() > 0 )
        {
            connectionThrottle = new ConnectionThrottle( config.getThrottle(), config.getThrottleLimit() );
        }
        startListeners();

        saveThread.scheduleAtFixedRate( new TimerTask()
        {
            @Override
            public void run()
            {
                if ( getReconnectHandler() != null )
                {
                    getReconnectHandler().save();
                }
            }
        }, 0, TimeUnit.MINUTES.toMillis( 5 ) );
        metricsThread.scheduleAtFixedRate( new Metrics(), 0, TimeUnit.MINUTES.toMillis( Metrics.PING_INTERVAL ) );

        Runtime.getRuntime().addShutdownHook( new Thread()
        {
            @Override
            public void run()
            {
                independentThreadStop( getTranslation( "restart" ), false );
            }
        } );
    }

    public void startListeners()
    {
        for ( final ListenerInfo info : config.getListeners() )
        {
            if ( info.isProxyProtocol() )
            {
                getLogger().log( Level.WARNING, "Using PROXY protocol for listener {0}, please ensure this listener is adequately firewalled.", info.getSocketAddress() );

                if ( connectionThrottle != null )
                {
                    connectionThrottle = null;
                    getLogger().log( Level.WARNING, "Since PROXY protocol is in use, internal connection throttle has been disabled." );
                }
            }

            ChannelFutureListener listener = new ChannelFutureListener()
            {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception
                {
                    if ( future.isSuccess() )
                    {
                        listeners.add( future.channel() );
                        getLogger().log( Level.INFO, "Listening on {0}", info.getSocketAddress() );
                    } else
                    {
                        getLogger().log( Level.WARNING, "Could not bind to host " + info.getSocketAddress(), future.cause() );
                    }
                }
            };
            new ServerBootstrap()
                    .channel( PipelineUtils.getServerChannel( info.getSocketAddress() ) )
                    .option( ChannelOption.SO_REUSEADDR, true ) // TODO: Move this elsewhere!
                    .childAttr( PipelineUtils.LISTENER, info )
                    .childHandler( unsafe().getFrontendChannelInitializer().getChannelInitializer() )
                    .group( eventLoops )
                    .localAddress( info.getSocketAddress() )
                    .bind().addListener( listener );

            if ( info.isQueryEnabled() )
            {
                Preconditions.checkArgument( info.getSocketAddress() instanceof InetSocketAddress, "Can only create query listener on UDP address" );

                ChannelFutureListener bindListener = new ChannelFutureListener()
                {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception
                    {
                        if ( future.isSuccess() )
                        {
                            listeners.add( future.channel() );
                            getLogger().log( Level.INFO, "Started query on {0}", future.channel().localAddress() );
                        } else
                        {
                            getLogger().log( Level.WARNING, "Could not bind to host " + info.getSocketAddress(), future.cause() );
                        }
                    }
                };
                new RemoteQuery( this, info ).start( PipelineUtils.getDatagramChannel(), new InetSocketAddress( info.getHost().getAddress(), info.getQueryPort() ), eventLoops, bindListener );
            }
        }
    }

    public void stopListeners()
    {
        for ( Channel listener : listeners )
        {
            getLogger().log( Level.INFO, "Closing listener {0}", listener );
            try
            {
                listener.close().syncUninterruptibly();
            } catch ( ChannelException ex )
            {
                getLogger().severe( "Could not close listen thread" );
            }
        }
        listeners.clear();
    }

    @Override
    public void stop()
    {
        stop( getTranslation( "restart" ) );
    }

    @Override
    public void stop(final String reason)
    {
        new Thread( "Shutdown Thread" )
        {
            @Override
            public void run()
            {
                independentThreadStop( reason, true );
            }
        }.start();
    }

    // This must be run on a separate thread to avoid deadlock!
    private void independentThreadStop(final String reason, boolean callSystemExit)
    {
        // Acquire the shutdown lock
        // This needs to actually block here, otherwise running 'end' and then ctrl+c will cause the thread to terminate prematurely
        shutdownLock.lock();

        // Acquired the shutdown lock
        if ( !isRunning )
        {
            // Server is already shutting down - nothing to do
            shutdownLock.unlock();
            return;
        }
        isRunning = false;

        stopListeners();
        getLogger().info( "Closing pending connections" );

        connectionLock.readLock().lock();
        try
        {
            getLogger().log( Level.INFO, "Disconnecting {0} connections", connections.size() );
            for ( UserConnection user : connections.values() )
            {
                user.disconnect( reason );
            }
        } finally
        {
            connectionLock.readLock().unlock();
        }

        try
        {
            Thread.sleep( 500 );
        } catch ( InterruptedException ex )
        {
        }

        if ( reconnectHandler != null )
        {
            getLogger().info( "Saving reconnect locations" );
            reconnectHandler.save();
            reconnectHandler.close();
        }
        saveThread.cancel();
        metricsThread.cancel();

        getLogger().info( "Disabling plugins" );
        for ( Plugin plugin : Lists.reverse( new ArrayList<>( pluginManager.getPlugins() ) ) )
        {
            try
            {
                plugin.onDisable();
                for ( Handler handler : plugin.getLogger().getHandlers() )
                {
                    handler.close();
                }
            } catch ( Throwable t )
            {
                getLogger().log( Level.SEVERE, "Exception disabling plugin " + plugin.getDescription().getName(), t );
            }
            getScheduler().cancel( plugin );
            plugin.getExecutorService().shutdownNow();
        }

        getLogger().info( "Closing IO threads" );
        eventLoops.shutdownGracefully();
        try
        {
            eventLoops.awaitTermination( Long.MAX_VALUE, TimeUnit.NANOSECONDS );
        } catch ( InterruptedException ex )
        {
        }

        getLogger().info( "Thank you and goodbye" );
        // Need to close loggers after last message!
        for ( Handler handler : getLogger().getHandlers() )
        {
            handler.close();
        }

        // Unlock the thread before optionally calling system exit, which might invoke this function again.
        // If that happens, the system will obtain the lock, and then see that isRunning == false and return without doing anything.
        shutdownLock.unlock();

        if ( callSystemExit )
        {
            System.exit( 0 );
        }
    }

    /**
     * Broadcasts a packet to all clients that is connected to this instance.
     *
     * @param packet the packet to send
     */
    public void broadcast(DefinedPacket packet)
    {
        connectionLock.readLock().lock();
        try
        {
            for ( UserConnection con : connections.values() )
            {
                con.unsafe().sendPacket( packet );
            }
        } finally
        {
            connectionLock.readLock().unlock();
        }
    }

    @Override
    public String getName()
    {
        return "BungeeCord";
    }

    @Override
    public String getVersion()
    {
        return ( BungeeCord.class.getPackage().getImplementationVersion() == null ) ? "unknown" : BungeeCord.class.getPackage().getImplementationVersion();
    }

    public final void reloadMessages()
    {
        Map<String, Format> cachedFormats = new HashMap<>();

        File file = new File( "messages.properties" );
        if ( file.isFile() )
        {
            try ( FileReader rd = new FileReader( file ) )
            {
                cacheResourceBundle( cachedFormats, new PropertyResourceBundle( rd ) );
            } catch ( IOException ex )
            {
                getLogger().log( Level.SEVERE, "Could not load custom messages.properties", ex );
            }
        }

        ResourceBundle baseBundle;
        try
        {
            baseBundle = ResourceBundle.getBundle( "messages" );
        } catch ( MissingResourceException ex )
        {
            baseBundle = ResourceBundle.getBundle( "messages", Locale.ENGLISH );
        }
        cacheResourceBundle( cachedFormats, baseBundle );

        messageFormats = Collections.unmodifiableMap( cachedFormats );
    }

    private void cacheResourceBundle(Map<String, Format> map, ResourceBundle resourceBundle)
    {
        Enumeration<String> keys = resourceBundle.getKeys();
        while ( keys.hasMoreElements() )
        {
            map.computeIfAbsent( keys.nextElement(), (key) -> new MessageFormat( resourceBundle.getString( key ) ) );
        }
    }

    @Override
    public String getTranslation(String name, Object... args)
    {
        Format format = messageFormats.get( name );
        return ( format != null ) ? format.format( args ) : "<translation '" + name + "' missing>";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<ProxiedPlayer> getPlayers()
    {
        connectionLock.readLock().lock();
        try
        {
            return Collections.unmodifiableCollection( new HashSet( connections.values() ) );
        } finally
        {
            connectionLock.readLock().unlock();
        }
    }

    @Override
    public int getOnlineCount()
    {
        return connections.size();
    }

    @Override
    public ProxiedPlayer getPlayer(String name)
    {
        connectionLock.readLock().lock();
        try
        {
            return connections.get( name );
        } finally
        {
            connectionLock.readLock().unlock();
        }
    }

    public UserConnection getPlayerByOfflineUUID(UUID uuid)
    {
        if ( uuid.version() != 3 )
        {
            return null;
        }
        connectionLock.readLock().lock();
        try
        {
            return connectionsByOfflineUUID.get( uuid );
        } finally
        {
            connectionLock.readLock().unlock();
        }
    }

    @Override
    public ProxiedPlayer getPlayer(UUID uuid)
    {
        connectionLock.readLock().lock();
        try
        {
            return connectionsByUUID.get( uuid );
        } finally
        {
            connectionLock.readLock().unlock();
        }
    }

    @Override
    public Map<String, ServerInfo> getServers()
    {
        return config.getServers();
    }

    @Override
    public ServerInfo getServerInfo(String name)
    {
        return getServers().get( name );
    }

    @Override
    @Synchronized("pluginChannels")
    public void registerChannel(String channel)
    {
        pluginChannels.add( channel );
    }

    @Override
    @Synchronized("pluginChannels")
    public void unregisterChannel(String channel)
    {
        pluginChannels.remove( channel );
    }

    @Override
    @Synchronized("pluginChannels")
    public Collection<String> getChannels()
    {
        return Collections.unmodifiableCollection( pluginChannels );
    }

    public PluginMessage registerChannels(int protocolVersion)
    {
        if ( protocolVersion >= ProtocolConstants.MINECRAFT_1_13 )
        {
            return new PluginMessage( "minecraft:register", String.join( "\00", Iterables.transform( pluginChannels, PluginMessage.MODERNISE ) ).getBytes( StandardCharsets.UTF_8 ), false );
        }

        return new PluginMessage( "REGISTER", String.join( "\00", pluginChannels ).getBytes( StandardCharsets.UTF_8 ), false );
    }

    @Override
    public int getProtocolVersion()
    {
        return ProtocolConstants.SUPPORTED_VERSION_IDS.get( ProtocolConstants.SUPPORTED_VERSION_IDS.size() - 1 );
    }

    @Override
    public String getGameVersion()
    {
        return ProtocolConstants.SUPPORTED_VERSIONS.get( 0 ) + "-" + ProtocolConstants.SUPPORTED_VERSIONS.get( ProtocolConstants.SUPPORTED_VERSIONS.size() - 1 );
    }

    @Override
    public ServerInfo constructServerInfo(String name, InetSocketAddress address, String motd, boolean restricted)
    {
        return constructServerInfo( name, (SocketAddress) address, motd, restricted );
    }

    @Override
    public ServerInfo constructServerInfo(String name, SocketAddress address, String motd, boolean restricted)
    {
        return new BungeeServerInfo( name, address, motd, restricted );
    }

    @Override
    public CommandSender getConsole()
    {
        return ConsoleCommandSender.getInstance();
    }

    @Override
    public void broadcast(String message)
    {
        broadcast( TextComponent.fromLegacy( message ) );
    }

    @Override
    public void broadcast(BaseComponent... message)
    {
        getConsole().sendMessage( message );
        for ( ProxiedPlayer player : getPlayers() )
        {
            player.sendMessage( message );
        }
    }

    @Override
    public void broadcast(BaseComponent message)
    {
        getConsole().sendMessage( message );
        for ( ProxiedPlayer player : getPlayers() )
        {
            player.sendMessage( message );
        }
    }

    public boolean addConnection(UserConnection con)
    {
        UUID offlineId = con.getPendingConnection().getOfflineId();
        if ( offlineId != null && offlineId.version() != 3 )
        {
            throw new IllegalArgumentException( "Offline UUID must be a name-based UUID" );
        }
        connectionLock.writeLock().lock();
        try
        {
            if ( connections.containsKey( con.getName() ) || connectionsByUUID.containsKey( con.getUniqueId() ) || connectionsByOfflineUUID.containsKey( offlineId ) )
            {
                return false;
            }
            connections.put( con.getName(), con );
            connectionsByUUID.put( con.getUniqueId(), con );
            connectionsByOfflineUUID.put( offlineId, con );
        } finally
        {
            connectionLock.writeLock().unlock();
        }
        return true;
    }

    public void removeConnection(UserConnection con)
    {
        connectionLock.writeLock().lock();
        try
        {
            // TODO See #1218
            if ( connections.get( con.getName() ) == con )
            {
                connections.remove( con.getName() );
                connectionsByUUID.remove( con.getUniqueId() );
                connectionsByOfflineUUID.remove( con.getPendingConnection().getOfflineId() );
            }
        } finally
        {
            connectionLock.writeLock().unlock();
        }
    }

    @Override
    public Collection<String> getDisabledCommands()
    {
        return config.getDisabledCommands();
    }

    @Override
    public Collection<ProxiedPlayer> matchPlayer(final String partialName)
    {
        Preconditions.checkNotNull( partialName, "partialName" );

        ProxiedPlayer exactMatch = getPlayer( partialName );
        if ( exactMatch != null )
        {
            return Collections.singleton( exactMatch );
        }

        return Sets.newHashSet( Iterables.filter( getPlayers(), new Predicate<ProxiedPlayer>()
        {

            @Override
            public boolean apply(ProxiedPlayer input)
            {
                return ( input == null ) ? false : input.getName().toLowerCase( Locale.ROOT ).startsWith( partialName.toLowerCase( Locale.ROOT ) );
            }
        } ) );
    }

    @Override
    public Title createTitle()
    {
        return new BungeeTitle();
    }

    @Override
    public Unsafe unsafe()
    {
        return unsafe;
    }
}
