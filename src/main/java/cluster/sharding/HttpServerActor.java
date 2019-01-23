package cluster.sharding;

import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.ActorSelection;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.cluster.Cluster;
import akka.cluster.Member;
import akka.cluster.MemberStatus;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocket;
import akka.japi.JavaPartialFunction;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HttpServerActor extends AbstractLoggingActor {
    private ActorSystem actorSystem = context().system();
    private ActorMaterializer actorMaterializer = ActorMaterializer.create(actorSystem);
    private final Cluster cluster = Cluster.get(actorSystem);
    private final Tree tree = new Tree("cluster", "cluster");

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(EntityMessage.Action.class, this::actionMessage)
                .build();
    }

    private void actionMessage(EntityMessage.Action action) {
        log().info("{} <-- {}", action, sender());
        if (action.action.equals("start")) {
            tree.add(action.member, action.shardId, action.entityId);
        } else if (action.action.equals("stop")) {
            tree.remove(action.member, action.shardId, action.entityId);
        }
        forwardActionMessage(action);
    }

    private void forwardActionMessage(EntityMessage.Action action) {
        if (action.forward) {
            cluster.state().getMembers().forEach(member -> {
                if (!cluster.selfMember().equals(member) && member.status().equals(MemberStatus.up())) {
                    forwardActionMessage(action.asNoForward(), member);
                }
            });
        }
    }

    private void forwardActionMessage(EntityMessage.Action action, Member member) {
        String path = member.address().toString() + self().path().toStringWithoutAddress();
        ActorSelection actorSelection = context().actorSelection(path);
        log().debug("{} -> {}", action, actorSelection);
        actorSelection.tell(action, self());
    }

    @Override
    public void preStart() {
        log().info("Start");
        startHttpServer();
    }

    private void startHttpServer() {
        int serverPort = 8080;

        try {
            CompletionStage<ServerBinding> serverBindingCompletionStage = Http.get(actorSystem)
                    .bindAndHandleSync(this::handleHttpRequest, ConnectHttp.toHost(InetAddress.getLocalHost().getHostName(), serverPort), actorMaterializer);

            serverBindingCompletionStage.toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (UnknownHostException e) {
            log().error(e, "Unable to access hostname");
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            log().error(e, "Monitor HTTP server error");
        } finally {
            log().info("HTTP server started on port {}", serverPort);
        }
    }

    private HttpResponse handleHttpRequest(HttpRequest httpRequest) {
        log().info("HTTP request '{}", httpRequest.getUri().path());
        switch (httpRequest.getUri().path()) {
            case "/":
                return htmlFileResponse("monitor.html");
            case "/d3/d3.v5.js":
                return jsFileResponse("d3/d3.v5.js");
            case "/monitor2":
                return htmlFileResponse("monitor2.html");
            case "/monitor3":
                return htmlFileResponse("monitor3.html");
            case "/d3/d3.js":
                return jsFileResponse("d3/d3.js");
            case "/d3/d3.geom.js":
                return jsFileResponse("d3/d3.geom.js");
            case "/d3/d3.layout.js":
                return jsFileResponse("d3/d3.layout.js");
            case "/events":
                return webSocketHandler(httpRequest);
            default:
                return HttpResponse.create().withStatus(404);
        }
    }

    private HttpResponse htmlFileResponse(String filename) {
        try {
            String fileContents = readFile(filename);
            return HttpResponse.create()
                    .withEntity(ContentTypes.TEXT_HTML_UTF8, fileContents)
                    .withStatus(StatusCodes.ACCEPTED);
        } catch (IOException e) {
            log().error(e, String.format("I/O error on file '%s'", filename));
            return HttpResponse.create().withStatus(StatusCodes.INTERNAL_SERVER_ERROR);
        }
    }

    private HttpResponse jsFileResponse(String filename) {
        try {
            String fileContents = readFile(filename);
            return HttpResponse.create()
                    .withEntity(ContentTypes.create(MediaTypes.APPLICATION_JAVASCRIPT, HttpCharsets.UTF_8), fileContents)
                    .withStatus(StatusCodes.ACCEPTED);
        } catch (IOException e) {
            log().error(e, String.format("I/O error on file '%s'", filename));
            return HttpResponse.create().withStatus(StatusCodes.INTERNAL_SERVER_ERROR);
        }
    }

    private String readFile(String filename) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filename);
        if (inputStream == null) {
            throw new FileNotFoundException(String.format("Filename '%s'", filename));
        } else {
            StringBuilder fileContents = new StringBuilder();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = br.readLine()) != null) {
                    fileContents.append(String.format("%s%n", line));
                }
            }
            return fileContents.toString();
        }
    }

    private HttpResponse webSocketHandler(HttpRequest httpRequest) {
        Flow<Message, Message, NotUsed> flow = Flow.<Message>create()
                .collect(new JavaPartialFunction<Message, Message>() {
                    @Override
                    public Message apply(Message message, boolean isCheck) {
                        if (isCheck && message.isText()) {
                            return null;
                        } else if (isCheck && !message.isText()) {
                            throw noMatch();
                        } else if (message.asTextMessage().isStrict()) {
                            return getTreeAsJson();
                        } else {
                            return TextMessage.create("");
                        }
                    }
                });

        return WebSocket.handleWebSocketRequestWith(httpRequest, flow);
    }

    private Message getTreeAsJson() {
        return TextMessage.create(tree.toJson());
    }

    @Override
    public void postStop() {
        log().info("Stop");
    }

    static Props props() {
        return Props.create(HttpServerActor.class);
    }

    public static class Tree implements Serializable {
        public final String name;
        public final String type;
        public final List<Tree> children = new ArrayList<>();

        public Tree(String name, String type) {
            this.name = name;
            this.type = type;
        }

        static Tree create(String name, String type) {
            return new Tree(name, type);
        }

        Tree children(Tree... children) {
            this.children.addAll(Arrays.asList(children));
            return this;
        }

        void add(String memberId, String shardId, String entityId) {
            removeEntity(entityId);
            Tree member = find(memberId, "member");
            if (member == null) {
                member = Tree.create(memberId, "member");
                children.add(member);
            }
            Tree shard = member.find(shardId, "shard");
            if (shard == null) {
                shard = Tree.create(shardId, "shard");
                member.children.add(shard);
            }
            Tree entity = shard.find(entityId, "entity");
            if (entity == null) {
                entity = Tree.create(entityId, "entity");
                shard.children.add(entity);
            }
        }

        void remove(String memberId, String shardId, String entityId) {
            Tree member = find(memberId, "member");
            if (member != null) {
                Tree shard = member.find(shardId, "shard");
                if (shard != null) {
                    Tree entity = shard.find(entityId, "entity");
                    shard.children.remove(entity);

                    if (shard.children.isEmpty()) {
                        member.children.remove(shard);
                    }
                }
                if (member.children.isEmpty()) {
                    children.remove(member);
                }
            }
        }

        void removeEntity(String entityId) {
            for (Tree member : children) {
                for (Tree shard : member.children) {
                    for (Tree entity : shard.children) {
                        if (entity.name.equals(entityId)) {
                            shard.children.remove(entity);
                            break;
                        }
                    }
                }
            }
        }

        Tree find(String memberId, String shardId, String entityId) {
            Tree member = find(memberId, "member");
            if (member != null) {
                Tree shard = member.find(shardId, "shard");
                if (shard != null) {
                    Tree entity = shard.find(entityId, "entity");
                    if (entity != null) {
                        return entity;
                    }
                }
            }
            return null;
        }

        Tree find(String name, String type) {
            if (this.name.equals(name) && this.type.equals(type)) {
                return this;
            } else {
                for (Tree child : children) {
                    Tree found = child.find(name, type);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        String toJson() {
            ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
            try {
                return ow.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                return String.format("{ \"error\" : \"%s\" }", e.getMessage());
            }
        }

        @Override
        public String toString() {
            return String.format("%s[%s, %s]", getClass().getSimpleName(), name, type);
        }
    }
}