import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import org.postgresql.util.PSQLException;


public class DbClient {
    private static final int PORT = 8000;
    private static final String DATABASE_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String DATABASE_USER = "userdb";
    private static final String DATABASE_PASSWORD = "password";
    private static final String API_URL = "https://api.nostr.watch/v1/public";
    private static final String INSERT_QUERY = "INSERT INTO relays (relay) VALUES (?)";
    private static final String SELECT_QUERY = "SELECT * FROM relays";

    public static void main(String[] args) throws InterruptedException {
        try {
            createDatabaseAndUser();
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        EventLoopGroup group = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new HttpServerInitializer());

            Channel channel = bootstrap.bind(PORT).sync().channel();
            System.out.println("Server started on port " + PORT);

            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void createDatabaseAndUser() throws SQLException {
        Properties adminProperties = new Properties();
        adminProperties.setProperty("user", "brian.hartford");
    
        try (Connection adminConnection = DriverManager.getConnection(DATABASE_URL, adminProperties)) {
            try (PreparedStatement createUserStatement = adminConnection.prepareStatement(
                    "CREATE ROLE userdb LOGIN PASSWORD 'password'")) {
                try {
                    createUserStatement.executeUpdate();
                } catch (PSQLException e) {
                    // User already exists, handle the error or skip creating the user
                    System.out.println("User already exists. Skipping user creation.");
                }
            }
    
            try (PreparedStatement createDbStatement = adminConnection.prepareStatement(
                    "CREATE DATABASE db OWNER userdb")) {
                try {
                    createDbStatement.executeUpdate();
                } catch (PSQLException e) {
                    // Database already exists, handle the error or skip creating the database
                    System.out.println("Database already exists. Skipping database creation.");
                }
            }
        } catch (SQLException e) {
            // Handle other SQLExceptions
            e.printStackTrace();
            throw new SQLException("Error occurred while creating database and user.");
        }
    
        Properties userProperties = new Properties();
        userProperties.setProperty("user", DATABASE_USER);
        userProperties.setProperty("password", DATABASE_PASSWORD);
    
        try (Connection userConnection = DriverManager.getConnection(DATABASE_URL, userProperties)) {
            try (PreparedStatement createTableStatement = userConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS relays (relay VARCHAR(255))")) {
                createTableStatement.executeUpdate();
            }
        }
    }


    static class MetricHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (request.uri().equals("/query")) {
                try {
                    String responseData = fetchDataFromAPI();
                    storeDataInDatabase(responseData);
                    String queryResult = retrieveDataFromDatabase();
                    sendResponse(ctx, HttpResponseStatus.OK, queryResult);
                } catch (Exception e) {
                    e.printStackTrace();
                    sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error occurred");
                }
            } else {
                sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Endpoint not found");
            }
        }

        private String fetchDataFromAPI() throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JSONArray jsonArray = new JSONArray(response.body());
            JSONArray outputJsonArray = new JSONArray();

            for (int i = 0; i < jsonArray.length(); i++) {
                String apiValue = jsonArray.getString(i);
                JSONObject obj = new JSONObject();
                obj.put("nostr_relay", apiValue);
                outputJsonArray.put(obj);
            }

            return outputJsonArray.toString();
        }


        private void storeDataInDatabase(String data) throws SQLException {
            // Parse and insert data into the database
            JSONArray dataArray = new JSONArray(data);
            Properties properties = new Properties();
            properties.setProperty("user", DATABASE_USER);
            properties.setProperty("password", DATABASE_PASSWORD);
            try (Connection connection = DriverManager.getConnection(DATABASE_URL, properties)) {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_QUERY)) {
                    for (int i = 0; i < dataArray.length(); i++) {
                        JSONObject item = dataArray.getJSONObject(i);
                        statement.setString(1, item.getString("nostr_relay"));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        }

        private String retrieveDataFromDatabase() throws SQLException {
            // Retrieve data from the database
            StringBuilder result = new StringBuilder();
            Properties properties = new Properties();
            properties.setProperty("user", DATABASE_USER);
            properties.setProperty("password", DATABASE_PASSWORD);
            try (Connection connection = DriverManager.getConnection(DATABASE_URL, properties)) {
                try (PreparedStatement statement = connection.prepareStatement(SELECT_QUERY)) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String relay = resultSet.getString("relay");
                            result.append("relay: ").append(relay).append("\n");
                        }
                    }
                }
            }
            return result.toString();
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(message, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    static class HttpServerInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            ChannelPipeline pipeline = ch.pipeline();
            pipeline.addLast(new HttpRequestDecoder());
            pipeline.addLast(new HttpResponseEncoder());
            pipeline.addLast(new HttpObjectAggregator(512 * 1024));
            pipeline.addLast(new MetricHandler());
        }
    }
}
