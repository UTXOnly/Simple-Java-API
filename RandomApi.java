import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sql.DataSource;

import org.postgresql.util.PSQLException;
import io.github.cdimascio.dotenv.Dotenv;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class RandomApi {
    private static final int PORT = 8000;
    private static final Dotenv dotenv = Dotenv.load();
    private static final String DBHOST = dotenv.get("DB_HOST");
    private static final String DBPORT = dotenv.get("DB_PORT");
    private static final String DATABASE_USER = dotenv.get("DB_USER");
    private static final String DATABASE_PASSWORD = dotenv.get("DB_PASSWORD");
    private static final String DATABASE_NAME = dotenv.get("DB_NAME");
    private static final String DATABASE_URL = String.format("jdbc:postgresql://%s:%s/%s", DBHOST, DBPORT, DATABASE_NAME);
    private static final String API_URL = "https://randomuser.me/api/";
    private static final String INSERT_QUERY = "INSERT INTO person (first_name, last_name, email, username) VALUES (?, ?, ?, ?)";
    private static final String SELECT_QUERY = "SELECT * FROM person ORDER BY id DESC LIMIT 10";

    private static DataSource dataSource;
    private static ExecutorService executorService;

    public static void main(String[] args) throws InterruptedException {
        try {
            createDatabaseAndUser();
            initializeDataSource();
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        EventLoopGroup group = new NioEventLoopGroup();
        executorService = Executors.newFixedThreadPool(2);

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
            executorService.shutdown();
        }
    }

    private static void createDatabaseAndUser() throws SQLException {
        Properties adminProperties = new Properties();
        adminProperties.setProperty("user", DATABASE_USER);
        adminProperties.setProperty("password", DATABASE_PASSWORD);

        try (Connection adminConnection = DriverManager.getConnection(DATABASE_URL, adminProperties)) {
            try (PreparedStatement createUserStatement = adminConnection.prepareStatement(
                    "CREATE ROLE " + DATABASE_USER + " LOGIN PASSWORD '" + DATABASE_PASSWORD + "'")) {
                try {
                    createUserStatement.executeUpdate();
                } catch (PSQLException e) {
                    System.out.println("User already exists. Skipping user creation.");
                }
            }

            try (PreparedStatement createDbStatement = adminConnection.prepareStatement(
                    "CREATE DATABASE " + DATABASE_NAME + " OWNER " + DATABASE_USER)) {
                try {
                    createDbStatement.executeUpdate();
                } catch (PSQLException e) {
                    System.out.println("Database already exists. Skipping database creation.");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new SQLException("Error occurred while creating database and user.");
        }

        Properties userProperties = new Properties();
        userProperties.setProperty("user", DATABASE_USER);
        userProperties.setProperty("password", DATABASE_PASSWORD);

        try (Connection userConnection = DriverManager.getConnection(DATABASE_URL, userProperties)) {
            try (PreparedStatement createTableStatement = userConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS person (id SERIAL PRIMARY KEY, first_name VARCHAR(255), last_name VARCHAR(255), email VARCHAR(255), username VARCHAR(255))")) {
                createTableStatement.executeUpdate();
            }
        }
    }

    private static void initializeDataSource() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(DATABASE_URL);
        config.setUsername(DATABASE_USER);
        config.setPassword(DATABASE_PASSWORD);
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
    }

    static class MetricHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            if (request.uri().equals("/query")) {
                executorService.submit(() -> handleQueryRequest(ctx));
            } else if (request.uri().equals("/fetch")) {
                executorService.submit(() -> handleFetchRequest(ctx));
            } else {
                sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Endpoint not found");
            }
        }

        private void handleQueryRequest(ChannelHandlerContext ctx) {
            try {
                String queryResult = retrieveDataFromDatabase();
                System.out.println("Query function completed");
                sendResponse(ctx, HttpResponseStatus.OK, queryResult);
            } catch (Exception e) {
                e.printStackTrace();
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error occurred");
            }
        }

        private void handleFetchRequest(ChannelHandlerContext ctx) {
            try {
                String responseData = fetchDataFromAPI();
                storeDataInDatabase(responseData);
                System.out.println("Data fetched and stored successfully.");
                sendResponse(ctx, HttpResponseStatus.OK, "Fetching data from API...");
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Error occurred while fetching and storing data.");
                sendResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Error occurred");
            }
        }

        private String fetchDataFromAPI() throws Exception {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .build();

            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                JSONObject jsonObject = new JSONObject(response.body());
                JSONArray resultsArray = jsonObject.getJSONArray("results");
                JSONArray outputJsonArray = new JSONArray();

                for (int i = 0; i < resultsArray.length(); i++) {
                    JSONObject result = resultsArray.getJSONObject(i);

                    String firstName = result.getJSONObject("name").getString("first");
                    String lastName = result.getJSONObject("name").getString("last");
                    String email = result.getString("email");
                    String username = result.getJSONObject("login").getString("username");

                    JSONObject obj = new JSONObject();
                    obj.put("first_name", firstName);
                    obj.put("last_name", lastName);
                    obj.put("email", email);
                    obj.put("username", username);

                    outputJsonArray.put(obj);
                }
                return outputJsonArray.toString();
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                throw new Exception("Error occurred while fetching data from the API.");
            }
        }

        private void storeDataInDatabase(String data) throws SQLException {
            JSONArray dataArray = new JSONArray(data);
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_QUERY)) {
                    for (int i = 0; i < dataArray.length(); i++) {
                        JSONObject item = dataArray.getJSONObject(i);
                        statement.setString(1, item.getString("first_name"));
                        statement.setString(2, item.getString("last_name"));
                        statement.setString(3, item.getString("email"));
                        statement.setString(4, item.getString("username"));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        }

        private String retrieveDataFromDatabase() throws SQLException {
            StringBuilder result = new StringBuilder();
            try (Connection connection = dataSource.getConnection()) {
                try (PreparedStatement statement = connection.prepareStatement(SELECT_QUERY)) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String firstName = resultSet.getString("first_name");
                            String lastName = resultSet.getString("last_name");
                            String email = resultSet.getString("email");
                            String username = resultSet.getString("username");

                            result.append("First Name: ").append(firstName).append("\n");
                            result.append("Last Name: ").append(lastName).append("\n");
                            result.append("Email: ").append(email).append("\n");
                            result.append("Username: ").append(username).append("\n");
                            result.append("\n");
                        }
                    }
                }
            }
            return result.toString();
        }

        private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
            ByteBuf buffer = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);

            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

            ctx.writeAndFlush(response);
        }
    }

    static class HttpServerInitializer extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel channel) {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(65536));
            pipeline.addLast(new MetricHandler());
        }
    }
}
