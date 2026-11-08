package com.example.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Endpoints that succeed or fail in different ways. Try them with curl and watch the console:
 * see examples/demo/README.md.
 */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final PaymentService payments;
    private final ReceiptMailer mailer;

    public OrderController(PaymentService payments, ReceiptMailer mailer) {
        this.payments = payments;
        this.mailer = mailer;
    }

    /** Succeeds: only the INFO lines are written. */
    @GetMapping("/orders/{id}")
    public String order(@PathVariable String id) {
        log.info("Loading order {}", id);
        log.debug("Order {} has 3 items, total 109.00", id);
        log.debug("Applied discount code SPRING10");
        return "order " + id + " ok\n";
    }

    /** Fails with an exception: a 500, with this request's DEBUG lines written before the error. */
    @GetMapping("/orders/{id}/pay")
    public String pay(@PathVariable String id, @RequestParam(defaultValue = "4111") String card) {
        log.info("Paying for order {}", id);
        log.debug("Order {} total 109.00, card ending {}", id, card.substring(Math.max(0, card.length() - 4)));
        payments.charge(id, card);
        mailer.sendReceipt(id);
        return "paid " + id + "\n";
    }

    /** Logs an ERROR but still returns 200: the DEBUG lines are written at the ERROR. */
    @GetMapping("/orders/{id}/ship")
    public String ship(@PathVariable String id) {
        log.info("Shipping order {}", id);
        log.debug("Primary carrier: ParcelCo, warehouse 3");
        log.error("ParcelCo rejected the shipment for order {}; using backup carrier", id);
        log.debug("Backup carrier accepted order {}", id);
        return "shipped " + id + " with backup carrier\n";
    }

    /** Ends with 503 without throwing: the DEBUG lines are written with a WARN line in front. */
    @GetMapping("/orders/{id}/refund")
    public String refund(@PathVariable String id) {
        log.info("Refunding order {}", id);
        log.debug("Refund service health check: DOWN");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "refund service unavailable");
    }

    /** Takes 70 seconds: its DEBUG lines are written while it is still running (flush-after is 30s here). */
    @GetMapping("/orders/{id}/stuck")
    public String stuck(@PathVariable String id) throws InterruptedException {
        log.info("Checking stock for order {}", id);
        log.debug("Calling inventory service for order {}", id);
        Thread.sleep(70_000);
        log.debug("Inventory service finally answered");
        return "checked " + id + "\n";
    }
}
