package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.UploadLink;
import com.vehiclemanagement.domain.User;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.repo.UploadLinkRepository;
import com.vehiclemanagement.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The secret links behind the public photo-intake page.
 *
 * <p><b>A link is a credential for one narrow act</b> -- adding a photo intake as one user -- and
 * is handled like one: 256 random bits, stored only as a hash, replaced to revoke, and checked
 * against the account on every use. That last part is what keeps the rule the security config
 * records: deactivating a person must end their access. An anonymous endpoint would break it; a
 * link that resolves to an account on each request does not.
 */
@Service
public class UploadLinkService {

    private static final Logger log = LoggerFactory.getLogger(UploadLinkService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Per user, not per IP: the token is the identity, and a phone's IP changes as it roams. */
    static final int MAX_UPLOADS = 60;
    static final long WINDOW_MS = 60 * 60 * 1000L;

    private final UploadLinkRepository links;
    private final UserRepository users;
    private final Map<Long, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();

    public UploadLinkService(UploadLinkRepository links, UserRepository users) {
        this.links = links;
        this.users = users;
    }

    /**
     * Make a new link for a user, replacing any existing one. Returns the token, which is the
     * only time it exists in the clear.
     */
    @Transactional
    public String generate(long userId, Long actorId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException.NotFound("User " + userId + " not found."));
        if (!user.isActive()) {
            throw new ApiException.Conflict(user.getName() + " is deactivated. Reactivate the "
                    + "account before giving them a link.");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        links.saveAndFlush(new UploadLink(userId, hash(token), actorId));
        log.info("upload link issued for user {} by {}", userId, actorId);
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<UploadLink> status(long userId) {
        return links.findById(userId);
    }

    @Transactional
    public void revoke(long userId) {
        if (links.existsById(userId)) {
            links.deleteById(userId);
            log.info("upload link revoked for user {}", userId);
        }
    }

    /**
     * The account a token belongs to.
     *
     * <p>One answer for every way it can be wrong -- never issued, revoked, replaced, or the
     * account deactivated -- so the response does not tell a guesser which part was close.
     */
    @Transactional(readOnly = true)
    public User resolve(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw invalid();
        }
        UploadLink link = links.findByTokenHash(hash(token)).orElseThrow(UploadLinkService::invalid);
        return users.findById(link.getUserId()).filter(User::isActive)
                .orElseThrow(UploadLinkService::invalid);
    }

    /** Count one upload against the user's hourly allowance; refuses when it is used up. */
    public void checkAllowance(long userId) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> stamps = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > WINDOW_MS) {
                stamps.pollFirst();
            }
            if (stamps.size() >= MAX_UPLOADS) {
                throw new ApiException(429, "Too many uploads this hour. Try again later.");
            }
            stamps.addLast(now);
        }
    }

    @Transactional
    public void touch(long userId) {
        links.touch(userId);
    }

    private static ApiException invalid() {
        return new ApiException.NotFound("This link is not valid. Ask for a new one.");
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
