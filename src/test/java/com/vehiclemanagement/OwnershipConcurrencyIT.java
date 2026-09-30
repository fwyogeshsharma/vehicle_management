package com.vehiclemanagement;

import com.vehiclemanagement.domain.Company;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.domain.Vehicle;
import com.vehiclemanagement.service.BodyTypeService;
import com.vehiclemanagement.service.CompanyService;
import com.vehiclemanagement.service.UserService;
import com.vehiclemanagement.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two people sell the same truck at the same moment.
 *
 * <p><b>This is the test the rejected design fails.</b> With ownership split across
 * {@code vehicle_x_company} and {@code vehicle_x_user}, both transactions read the other's table
 * before it commits, see no owner, insert, and commit — leaving two owners with no constraint
 * violated and no error raised. Nothing in the application layer can prevent that without
 * SERIALIZABLE or an explicit row lock.
 *
 * <p>With ownership on the vehicle row, the second transaction blocks on that row's lock until
 * the first commits, then overwrites it. One owner, every time, and no locking code anywhere.
 */
class OwnershipConcurrencyIT extends DatabaseTest {

    @Autowired BodyTypeService bodyTypes;
    @Autowired UserService userService;
    @Autowired CompanyService companyService;
    @Autowired VehicleService vehicleService;
    @Autowired PlatformTransactionManager txManager;

    private long truckId;
    private long companyAId;
    private long companyBId;
    private long driverId;

    @BeforeEach
    void setUp() {
        resetDomainData();
        Long openBody = bodyTypes.ensure("Open body").getId();
        User driver = userService.create("Ramesh Kumar", "9811008120", UserType.BOTH);
        Company a = companyService.create("Kumar Roadways");
        Company b = companyService.create("Patel Freight");
        Vehicle truck = vehicleService.create("MH12AB1234", openBody, null, driver.getId(),
                (short) 2, (short) 6, "20 Ton", new BigDecimal("22.0"));
        truckId = truck.getId();
        companyAId = a.getId();
        companyBId = b.getId();
        driverId = driver.getId();
    }

    @Test
    @DisplayName("two concurrent sales to different companies leave exactly one owner")
    void concurrent_sales_to_two_companies() throws Exception {
        runConcurrently(
                () -> vehicleService.setOwner(truckId, companyAId, null),
                () -> vehicleService.setOwner(truckId, companyBId, null));

        assertExactlyOneOwner();
        Long owner = jdbc.queryForObject(
                "SELECT owner_company_id FROM vehicles WHERE id = ?", Long.class, truckId);
        assertThat(owner).isIn(companyAId, companyBId);
    }

    @Test
    @DisplayName("a company and a person selling at once cannot both win")
    void concurrent_sale_to_a_company_and_to_a_person() throws Exception {
        vehicleService.setOwner(truckId, companyAId, null);

        runConcurrently(
                () -> vehicleService.setOwner(truckId, companyBId, null),
                () -> vehicleService.setOwner(truckId, null, driverId));

        // The case the two-table design gets wrong: one side writes a company, the other writes
        // a person, and nothing stops both. ck_vehicles_one_owner makes it unrepresentable.
        assertExactlyOneOwner();
    }

    /**
     * Runs both operations in genuinely separate transactions, started close enough together to
     * overlap. A latch rather than a sleep: the point is that they contend, not that they are
     * slow.
     */
    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> inTransaction(ready, go, first));
            Future<?> f2 = pool.submit(() -> inTransaction(ready, go, second));

            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            // One of the two may legitimately fail (a lock timeout, or a constraint firing on a
            // stale read). What must never happen is BOTH succeeding into an inconsistent row —
            // that is what assertExactlyOneOwner checks. So swallow failures here deliberately.
            settle(f1);
            settle(f2);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void inTransaction(CountDownLatch ready, CountDownLatch go, Runnable work) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            ready.countDown();
            try {
                if (!go.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            work.run();
        });
    }

    private void settle(Future<?> f) {
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (Exception expectedSometimes) {
            // see runConcurrently
        }
    }

    /** The whole point: whatever happened above, the row is still coherent. */
    private void assertExactlyOneOwner() {
        Integer owners = jdbc.queryForObject("""
                SELECT num_nonnulls(owner_company_id, owner_user_id)
                  FROM vehicles WHERE id = ?
                """, Integer.class, truckId);
        assertThat(owners)
                .as("a vehicle must have exactly one owner, whatever raced")
                .isEqualTo(1);

        // There is nothing else to check any more, and that is the point: since 002 removed the
        // OWNER rows, ownership exists in exactly one place, so "exactly one owner" is a
        // single-row question with a single-row answer. It used to also need a second assertion
        // about vehicle_x_user, which is precisely the duplication that got removed.
    }
}
