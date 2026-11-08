# debug-on-failure demo

A small Spring Boot shop for trying debug-on-failure. It is not part of the released modules.

## Run it

In IntelliJ, open the main `debug-on-failure` project, right-click `examples/demo/pom.xml` and choose **Add as Maven Project**, then run `ShopApplication`. The demo then uses the library's code directly, so changes to the library are picked up without installing anything.

From a terminal, install the library into your local Maven repository first, then start the demo:

```
mvn install -DskipTests
cd examples/demo
mvn spring-boot:run
```

Run `mvn install` again after changing the library; the demo uses the installed copy.

At startup, look for:

```
INFO  debug-on-failure: collecting DEBUG lines from [com.example.shop]; they are written only when a request or task fails
INFO  debug-on-failure: covering the @Scheduled and @Async methods of [NightlyReport, ReceiptMailer]
```

## Try it

Run each command in another terminal and watch the application's console.

| Command | What happens | What the console shows |
| --- | --- | --- |
| `curl localhost:8080/orders/42` | Succeeds | Only the INFO line. The two DEBUG lines are thrown away. |
| `curl "localhost:8080/orders/42/pay?card=4242000000000000"` | Payment is declined, 500 | A WARN naming `GET /orders/{id}/pay`, then the request's DEBUG lines, then Spring's error |
| `curl localhost:8080/orders/42/ship` | Logs an ERROR but returns 200 | The DEBUG lines just before the ERROR, then the next DEBUG line written straight away |
| `curl localhost:8080/orders/42/refund` | Returns 503 | A WARN with `status 503`, then the DEBUG line |
| `curl localhost:8080/orders/42/stuck` | Takes 70 seconds | After 30 to 60 seconds, a WARN saying `GET /orders/{id}/stuck` is still running, with its DEBUG line so far. When it finishes, it succeeds, so the last DEBUG line is thrown away. |
| `curl localhost:8080/orders/42/pay` | Succeeds; the async receipt also succeeds | INFO lines only |
| `curl localhost:8080/orders/9001/pay` | Request succeeds; the async receipt fails | A WARN naming `ReceiptMailer.sendReceipt`, then the receipt's DEBUG lines, on the async thread |
| (nothing, just wait) | The scheduled report runs every 20 seconds and fails every third run | On failed runs, a WARN naming `NightlyReport.build`, then its DEBUG lines |
