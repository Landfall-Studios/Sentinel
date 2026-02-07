package world.landfall.sentinel.context;

import world.landfall.sentinel.DenialReason;

public interface LoginGatekeeper {
    void allowLogin(LoginContext ctx);
    void denyLogin(LoginContext ctx, DenialReason reason);
    boolean supportsBypassRouting();
}
