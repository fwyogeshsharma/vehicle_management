package com.vehiclemanagement.bootstrap;

import com.vehiclemanagement.config.VehicleManagementProperties;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.domain.UserRole;
import com.vehiclemanagement.domain.UserType;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first administrator, so a fresh database is reachable at all.
 *
 * <p>Runs only when nobody can already administer the system, and only when all of
 * {@code VM_ADMIN_USERNAME}, {@code VM_ADMIN_PASSWORD} and {@code VM_ADMIN_MOBILE} are set.
 *
 * <p><b>There is deliberately no default password.</b> A built-in {@code admin/admin} is how this
 * class of application gets taken over: it survives the first deploy, nobody changes it, and it
 * is the first thing anyone tries. Missing settings mean the bootstrap is skipped and says so,
 * which is a loud, fixable state — unlike a working account with a known password.
 *
 * <p>Runs at {@code @Order(5)}: after the reference-data seeder, before the sample data.
 */
@Component
@Order(5)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final VehicleManagementProperties properties;
    private final UserRepository users;
    private final UserService userService;

    public AdminBootstrap(VehicleManagementProperties properties, UserRepository users,
                          UserService userService) {
        this.properties = properties;
        this.users = users;
        this.userService = userService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByUserTypeAndUsernameIsNotNull(UserType.ADMIN)) {
            return;
        }

        VehicleManagementProperties.Admin admin = properties.getAdmin();
        if (!admin.isComplete()) {
            log.warn("No administrator can sign in, and no bootstrap administrator is configured. "
                     + "Set VM_ADMIN_USERNAME, VM_ADMIN_PASSWORD and VM_ADMIN_MOBILE and restart "
                     + "to create one. (There is no default account, on purpose.)");
            return;
        }

        User created = userService.create(admin.getName(), admin.getMobile(), UserType.ADMIN);
        userService.setLogin(created.getId(), admin.getUsername(), admin.getPassword(),
                UserRole.ADMIN);
        log.info("Created the bootstrap administrator '{}'. This runs only while no administrator "
                 + "can sign in.", admin.getUsername());
    }
}
