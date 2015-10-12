<!--- Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com> -->
# WebSockets

[WebSockets](https://en.wikipedia.org/wiki/WebSocket) are sockets that can be used from a web browser based on a protocol that allows two way full duplex communication.  The client can send messages and the server can receive messages at any time, as long as there is an active WebSocket connection between the server and the client.

Modern HTML5 compliant web browsers natively support WebSockets via a JavaScript WebSocket API.  However WebSockets are not limited in just being used by WebBrowsers, there are many WebSocket client libraries available, allowing for example servers to talk to each other, and also native mobile apps to use WebSockets.  Using WebSockets in these contexts has the advantage of being able to reuse the existing TCP port that a Play server uses.

## Handling WebSockets

Until now, we were using `Action` instances to handle standard HTTP requests and send back standard HTTP responses. WebSockets use a different mechanism and can’t be handled via a standard [`Action`](api/scala/play/api/mvc/Action$.html).  Instead, they are handled by returning instances of [`WebSocket`](api/scala/play/api/mvc/WebSocket.html).

### Writing an echo WebSocket

The simplest type of WebSocket to write in Play would be an echo WebSocket - every message that it receives is simply echoed back.  This is what it looks like:

@[echo](code/ScalaWebSockets.scala)

You can see here that Play models a WebSocket as an Akka streams `Flow`.  Messages from the client are the input of the flow, and messages to the client are the output of the flow.  In the example above we are doing nothing the to the incoming messages, they are simply sent back out. We could also apply a transformation to them:

@[hello-echo](code/ScalaWebSockets.scala)

Now each message sent back to the client is going to be prefixed with `Hello`.

### Exposing and using a WebSocket

We've seen how to write the code for handling the WebSocket, but like for actions, we need to route requests to this handler.  This is done in exactly the same way as routing for actions, simply add a route to the correct path with the `GET` method in your routes file:

@[contents](scalaguide.async.websockets.routes)

You can of course extract parameters from the path and query string, as for normal actions, and pass them to the handler.

Connecting to the WebSocket from a web browser can be done using JavaScript, for example, the code below shows a simple example of connecting to a WebSocket from a browser:

```js
var ws = new WebSocket("http://localhost:9000/echo");
ws.onopen = function() {
  ws.send("test");
}
ws.onmessage = function(msg) {
  console.log(msg.data);
}
```

One problem with the above code is that it hard codes the URL in the JavaScript.  A good practice is to put the URL in the web page that opens the WebSocket, for example, in a meta tag.  The reverse router can help here:

@[contents](scalaguide/async/websockets.scala.html)

The URL can then be read from the meta tag in the JavaScript:

```js
var webSocketUrl = document.querySelector("meta[name='webSocketUrl']")
  .getAttribute("content");
var ws = new WebSocket(webSocketUrl);
```

### WebSocket message types and transformation

In the examples so far we've been handling WebSocket messages as a `String`.  WebSocket messages aren't always just String's, in fact Play models messages using [`Message`](api/scala/play/api/http/websocket/Message.html), and then uses a [`MessageFlowTransformer`](api/scala/play/api/mvc/WebSocket.MessageFlowTransformer.html) typeclass to transform text WebSocket messages to Strings.  WebSockets also support binary messages - Play's `String` `MessageFlowTransformer` will close the WebSocket with an error if it receives binary messages.  To handle binary messages, you can use `ByteString`:

@[bytestring-echo](code/ScalaWebSockets.scala)

One common protocol used over WebSockets is json, and Play provides some out of the box support for handling messages as `JsValue`:

@[jsvalue-echo](code/ScalaWebSockets.scala)

Of course a custom `MessageFlowTransformer` could be written to transform the `JsValue`'s to/from a higher level model object, and Play provides a utility for doing that out of the box:

@[js-model-echo](code/ScalaWebSockets.scala)

### Disconnected flows

In all the examples so far, we've seen what it looks like to create synchronous message passing style WebSockets, where there's a one to one mapping of input messages to output messages.  In many cases however, there may be no relation between input messages and output messages.  At first glance it may therefore appear unintuitive that a `Flow` is used to model a WebSocket, since a `Flow` evokes images of messages flowing in, being transformed, and then flowing out.  But a `Flow` does not have to be implemented in that way - a `Flow` is simply something with an input for stream and an output for a stream, whether the input and the output are connected at all is not specified.

Akka streams provides a `Flow.wrap` constructor for creating disconnected flows, this takes a `Sink` to handle incoming messages, and a `Source` to produce outgoing messages.  For example, below is a WebSocket that outputs a tick every second, and writes every incoming message to the console:

@[disconnected](code/ScalaWebSockets.scala)

Getting a little more complex, here is a primitive stop watch, which uses `java.util.concurrent` primitives for sharing state between the `Sink` and `Source` streams:

@[stopwatch](code/ScalaWebSockets.scala)

```scala
def stopwatch = WebSocket.accept[String, String] { req =>
  val running = new AtomicBoolean()
  val counter = new AtomicLong()
  val control = Sink.foreach[String] {
    case "start" => running.set(true)
    case "stop" => running.set(false)
    case "reset" => counter.set(0)
  }
  val tick = Source(initialDelay = 0.seconds, interval = 1.second, tick = "tick")
    .filter(_ => running.get)
    .map(_ => counter.getAndIncrement.toString)
  
  Flow.wrap(control, tick)(Keep.none)
}
```



You may also want to combine connected request/response messages with disconnected messages in the one flow, this can be done by creating a graph using broadcast and merge directives in Akka streams - here is the same stop watch, except that it ticks only once per minute, and allows the client to immediately request the current time from the stop watch:

@[stopwatch-with-request](code/ScalaWebSockets.scala)

## Handling WebSockets with actors

In some situations actors make a great abstraction for handling WebSockets. Akka streams provides some handy methods for handling messages received by a `Sink` with an actor, and for forwarding messages received by an actor to a `Source`.  Here's an example of a very simple chatroom that uses the `Source.actorRef` and `Sink.actorRef` constructors in combination with a chat room actor:

@[actor-chatroom](code/ScalaWebSockets.scala)

The example above works fine if you have an existing actor to handle all WebSockets.  Often though you may want to handle each WebSocket with a new actor bound to the lifecycle of the WebSocket.  A limitation of using `Sink.actorRef` and `Source.actorRef` in that context is that your actor can't send messages until it receives the `Source.actorRef`, making for some less than ideal state management.

We can also have Akka streams create the actor to handle messages for us using `Sink.actorRef`, however, a problem with this is that as a first step you need to send the `Source` actor to the `Sink` actor if you want it to be able to send messages - this makes handling messages a little complex since the actor won't have anything to send to from the start of its lifecycle.  To address this, Play provides a helper for creating a single actor to receive WebSocket messages.  Here is our stopwatch actor from above implemented using an actor to handle concurrency:

@[actor-stopwatch](code/ScalaWebSockets.scala)

### Detecting when an actor WebSocket has closed

Detecting when an actor WebSocket has closed depends on which actor integration you are using.  If using `Source.actorRef`, you can detect if the WebSocket is closed by [watching](http://doc.akka.io/docs/akka/snapshot/general/supervision.html#What_Lifecycle_Monitoring_Means) the source actor.

### Closing a WebSocket

Play will automatically close the WebSocket when your actor that handles the WebSocket terminates.  So, to close the WebSocket, send a `PoisonPill` to your own actor:

@[actor-stop](code/ScalaWebSockets.scala)

## Rejecting a WebSocket

Sometimes you may wish to reject a WebSocket request, for example, if the user must be authenticated to connect to the WebSocket, or if the WebSocket is associated with some resource, whose id is passed in the path, but no resource with that id exists.  Play provides `tryAcceptWithActor` to address this, allowing you to return either a result (such as forbidden, or not found), or the actor to handle the WebSocket with:

@[actor-try-accept](code/ScalaWebSockets.scala)

## Advanced WebSocket usage

### Handling control messages

### Closing with a custom close code

