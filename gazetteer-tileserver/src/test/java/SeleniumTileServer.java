import io.gazetteer.tileserver.TileServerHandler;
import io.gazetteer.tilestore.TileReader;
import io.gazetteer.tilestore.postgis.PostgisConfig;
import io.gazetteer.tilestore.postgis.PostgisLayer;
import io.gazetteer.tilestore.postgis.PostgisTileReader;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.yaml.snakeyaml.error.YAMLException;
import picocli.CommandLine;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

@CommandLine.Command(description = "Start a selenium tile server")
public class SeleniumTileServer implements Runnable {

  @CommandLine.Parameters(index = "0", paramLabel = "POSTGRES_DATABASE", description = "The Postgres database.")
  private String database;

  @CommandLine.Parameters(index = "1", paramLabel = "CONFIG_FILE", description = "The YAML configuration config.")
  private Path config;

  private EventLoopGroup bossGroup;

  private EventLoopGroup workerGroup;

  private Channel channel;

  private String url = "http://localhost:8081/";

  @Override
  public void run() {
    try {
      start();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }

    WebDriver driver = new ChromeDriver();
    driver.manage().window().maximize();
    driver.get(url);

    try (final WatchService watchService = FileSystems.getDefault().newWatchService()) {
      config.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
      while (true) {
        final WatchKey wk = watchService.take();
        for (WatchEvent<?> event : wk.pollEvents()) {
          final Path changed = (Path) event.context();
          if (config.getFileName().equals(changed.getFileName())) {
            try {
              stop();
              start();
              driver.get(url);
            } catch (YAMLException e) {
              e.printStackTrace();
            } catch (FileNotFoundException e) {
              e.printStackTrace();
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }
        wk.reset();
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  public void start() throws FileNotFoundException, InterruptedException {
    InternalLoggerFactory.setDefaultFactory(Slf4JLoggerFactory.INSTANCE);
    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup();
    List<PostgisLayer> layers = PostgisConfig.load(new FileInputStream(config.toFile())).getLayers();
    TileReader tileReader = new PostgisTileReader(database, layers);
    ServerBootstrap b = new ServerBootstrap();
    b.option(ChannelOption.SO_BACKLOG, 1024);
    b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .handler(new LoggingHandler(LogLevel.INFO))
        .childHandler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              public void initChannel(SocketChannel ch) throws FileNotFoundException {
                ChannelPipeline p = ch.pipeline();
                p.addLast(new HttpServerCodec());
                p.addLast(new HttpObjectAggregator(512 * 1024));
                p.addLast(
                    new CorsHandler(
                        CorsConfigBuilder.forOrigin("*")
                            .allowedRequestMethods(HttpMethod.POST)
                            .build()));
                p.addLast(new HttpServerExpectContinueHandler());
                p.addLast(new TileServerHandler(tileReader));
              }
            });
    channel = b.bind("localhost", 8081).sync().channel();
  }

  public void stop() {
    channel.close();
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
  }

  public static void main(String[] args) {
    CommandLine.run(new SeleniumTileServer(), args);
  }
}
