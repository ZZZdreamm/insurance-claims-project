package com.kmultan.claims.domain.auth;

/**
 * Who can do what:
 * <ul>
 *   <li>POLICYHOLDER — submits claims, sees and withdraws only their own</li>
 *   <li>ADJUSTER — review queue: claim/unclaim, approve/reject what they hold; reads all claims</li>
 *   <li>FINANCE — retries failed payouts, replays dead letters; reads all claims</li>
 *   <li>ADMIN — everything</li>
 *   <li>SERVICE — machine accounts (assessment-service reads photos)</li>
 * </ul>
 */
public enum Role { POLICYHOLDER, ADJUSTER, FINANCE, ADMIN, SERVICE }
