import com.google.gson.Gson;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.impl.launcher.*;
import io.vertx.core.Starter;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.vertx.ext.web.handler.sockjs.BridgeEventType.*;


/**
 * Created by den4ik on 05-Apr-16.
 */
public class Server extends AbstractVerticle {

    private Logger logger = LoggerFactory.getLogger(Server.class);
    private SockJSHandler handler = null;
    private AtomicInteger online = new AtomicInteger(0);

    public void start() throws Exception {

        if(!deploy()){
            logger.error("Failed to deploy the server");
            return;
        }
        handle();
    }

    private boolean deploy (){
        int hostPort = getFreePort();

        if (hostPort<0) return false;

        Router router = Router.router(vertx);
        handler = SockJSHandler.create(vertx);

        router.route("/eventbus/*").handler(handler);
        router.route().handler(StaticHandler.create());

        vertx.createHttpServer().requestHandler(router::accept).listen(hostPort);

        try{
            String addr = InetAddress.getLocalHost().getHostAddress();
            logger.info("Access to CHAT is available by the following address http://" + addr + ":" + hostPort);
        }catch (UnknownHostException e){
            logger.error("Failed to get the localhost address: " + e.toString());
            return false;
        }
        return true;
    }

    private int getFreePort(){
        int hostPort = 8080;

        if(VertxCommandLauncher.getProcessArguments() != null && VertxCommandLauncher.getProcessArguments().size() > 0){
            try{
                hostPort = Integer.valueOf(VertxCommandLauncher.getProcessArguments().get(0));
            }catch (NumberFormatException e ){
                logger.warn("Invalid port: " + VertxCommandLauncher.getProcessArguments().get(0));
            }
        }
        if (hostPort < 0 || hostPort > 65535){
            hostPort = 8080;
        }
        return getFreePort(hostPort);
    }

    private int getFreePort(int hostPort){
        try{
            ServerSocket socket = new ServerSocket(hostPort);
            int port = socket.getLocalPort();
            socket.close();
            return port;
        }catch (BindException e){
            if(hostPort != 0) return getFreePort(0);
            logger.error("Failed to get the free port: " + e.toString());
            return -1;
        }catch (IOException e){
            logger.error("Failed to get the free port: " + e.toString());
            return -1;
        }
    }

    private void handle (){
        BridgeOptions opts = new BridgeOptions()
                .addInboundPermitted(new PermittedOptions().setAddress("chat.to.server"))
                .addOutboundPermitted(new PermittedOptions().setAddress("chat.to.client"));

        handler.bridge(opts, event ->{
            if(event.type() == PUBLISH)
                publishEvent(event);

            if(event.type() == REGISTER)
                registerEvent(event);

            if(event.type() == SOCKET_CLOSED)
                closeEvent(event);

            event.complete(true);
        });
    }

    private boolean publishEvent(BridgeEvent event){
        if(event.getRawMessage() != null && event.getRawMessage().getString("address").equals("chat.to.server")){
            String message = event.getRawMessage().getString("body");
            if(!verifyMessage(message))
                return false;

            String host = event.socket().remoteAddress().host();
            int port = event.socket().remoteAddress().port();

            Map<String, Object> publicNotice = createPublicNotice(host, port, message);
                vertx.eventBus().publish("chat.to.client", new Gson().toJson(publicNotice));
                return true;
            }else {
                return false;
            }
        }

    private Map<String, Object> createPublicNotice(String host, int port, String message){
        Date time = Calendar.getInstance().getTime();

        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "publish");
        notice.put("time", time.toString());
        notice.put("host", host);
        notice.put("port", port);
        notice.put("message", message);
        return notice;
    }

    private void registerEvent(BridgeEvent event){
        if(event.getRawMessage() !=null && event.getRawMessage().getString("address").equals("chat.to.client"))
            new Thread(() ->
            {Map<String, Object> registerNotice = createRegisterNotice();
                vertx.eventBus().publish("chat.to.client",new Gson().toJson(registerNotice));
            }
            ).start();
    }

    private Map<String, Object> createRegisterNotice(){
        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "register");
        notice.put("online", online.incrementAndGet());
        return notice;
    }

    private void closeEvent(BridgeEvent event){
        new Thread(() ->
        {
            Map<String, Object> closeNotice = createCloseNotice();
            vertx.eventBus().publish("client.to.server",new Gson().toJson(closeNotice));
        }).start();
    }

    private Map<String, Object> createCloseNotice(){
        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "close");
        notice.put("online", online.decrementAndGet());
        return notice;
    }

    private boolean verifyMessage(String message){
        return message.length() > 0 && message.length() <= 140;
    }

}
