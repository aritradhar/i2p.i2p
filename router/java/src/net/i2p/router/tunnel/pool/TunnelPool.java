package net.i2p.router.tunnel.pool;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import net.i2p.data.Hash;
import net.i2p.data.Lease;
import net.i2p.data.LeaseSet;
import net.i2p.data.TunnelId;

import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.TunnelInfo;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 *
 */
public class TunnelPool {
    private RouterContext _context;
    private Log _log;
    private TunnelPoolSettings _settings;
    private ArrayList _tunnels;
    private TunnelPeerSelector _peerSelector;
    private TunnelPoolManager _manager;
    private boolean _alive;
    private long _lifetimeProcessed;
    private TunnelInfo _lastSelected;
    private long _lastSelectionPeriod;
    private int _expireSkew;
    private long _started;
    private long _lastRateUpdate;
    private long _lastLifetimeProcessed;
    private final String _rateName;
    private static final int TUNNEL_LIFETIME = 10*60*1000;
    
    public TunnelPool(RouterContext ctx, TunnelPoolManager mgr, TunnelPoolSettings settings, TunnelPeerSelector sel) {
        _context = ctx;
        _log = ctx.logManager().getLog(TunnelPool.class);
        _manager = mgr;
        _settings = settings;
        _tunnels = new ArrayList(settings.getLength() + settings.getBackupQuantity());
        _peerSelector = sel;
        _alive = false;
        _lastSelectionPeriod = 0;
        _lastSelected = null;
        _lifetimeProcessed = 0;
        _expireSkew = _context.random().nextInt(90*1000);
        _started = System.currentTimeMillis();
        _lastRateUpdate = _started;
        _lastLifetimeProcessed = 0;
        _rateName = "tunnel.Bps." +
                    (_settings.isExploratory() ? "exploratory" : _settings.getDestinationNickname()) +
                    (_settings.isInbound() ? ".in" : ".out");
        refreshSettings();
    }
    
    public void startup() {
        _alive = true;
        _started = System.currentTimeMillis();
        _lastRateUpdate = _started;
        _lastLifetimeProcessed = 0;
        _manager.getExecutor().repoll();
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            // we just reconnected and didn't require any new tunnel builders.
            // however, we /do/ want a leaseSet, so build one
            LeaseSet ls = null;
            synchronized (_tunnels) {
                ls = locked_buildNewLeaseSet();
            }

            if (ls != null)
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
        }
        _context.statManager().createRateStat(_rateName,
                               "Tunnel Bandwidth", "Tunnels", 
                               new long[] { 5*60*1000l });
    }
    
    public void shutdown() {
        _alive = false;
        _lastSelectionPeriod = 0;
        _lastSelected = null;
    }

    TunnelPoolManager getManager() { return _manager; }
    
    void refreshSettings() {
        if (_settings.getDestination() != null) {
            return; // don't override client specified settings
        } else {
            if (_settings.isExploratory()) {
                Properties props = _context.router().getConfigMap();
                if (_settings.isInbound())
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY, props);
                else
                    _settings.readFromProperties(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY, props);
            }
        }
    }
    
    /** 
     * when selecting tunnels, stick with the same one for a brief 
     * period to allow batching if we can.
     */
    private long curPeriod() {
        long period = _context.clock().now();
        long ms = period % 1000;
        if (ms > 500)
            period = period - ms + 500;
        else
            period = period - ms;
        return period;
    }
    
    private long getLifetime() { return System.currentTimeMillis() - _started; }
    
    /**
     * Pull a random tunnel out of the pool.  If there are none available but
     * the pool is configured to allow 0hop tunnels, this builds a fake one
     * and returns it.
     *
     */
    public TunnelInfo selectTunnel() { return selectTunnel(true); }
    private TunnelInfo selectTunnel(boolean allowRecurseOnFail) {
        boolean avoidZeroHop = ((getSettings().getLength() + getSettings().getLengthVariance()) > 0);
        
        long period = curPeriod();
        synchronized (_tunnels) {
            if (_lastSelectionPeriod == period) {
                if ( (_lastSelected != null) && 
                     (_lastSelected.getExpiration() > period) &&
                     (_tunnels.contains(_lastSelected)) )
                    return _lastSelected;
            }
            _lastSelectionPeriod = period;
            _lastSelected = null;

            if (_tunnels.size() <= 0) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": No tunnels to select from");
            } else {
                Collections.shuffle(_tunnels, _context.random());
                
                // if there are nonzero hop tunnels and the zero hop tunnels are fallbacks, 
                // avoid the zero hop tunnels
                TunnelInfo backloggedTunnel = null;
                if (avoidZeroHop) {
                    for (int i = 0; i < _tunnels.size(); i++) {
                        TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                        if ( (info.getLength() > 1) && (info.getExpiration() > _context.clock().now()) ) {
                            // avoid outbound tunnels where the 1st hop is backlogged
                            if (_settings.isInbound() || !_context.commSystem().isBacklogged(info.getPeer(1))) {
                                _lastSelected = info;
                                return info;
                            } else {
                                backloggedTunnel = info;
                            }
                        }
                    }
                    // return a random backlogged tunnel
                    if (backloggedTunnel != null) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn(toString() + ": All tunnels are backlogged");
                        return backloggedTunnel;
                    }
                }
                // ok, either we are ok using zero hop tunnels, or only fallback tunnels remain.  pick 'em
                // randomly
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                    if (info.getExpiration() > _context.clock().now()) {
                        // avoid outbound tunnels where the 1st hop is backlogged
                        if (_settings.isInbound() || info.getLength() <= 1 ||
                            !_context.commSystem().isBacklogged(info.getPeer(1))) {
                            //_log.debug("Selecting tunnel: " + info + " - " + _tunnels);
                            _lastSelected = info;
                            return info;
                        } else {
                            backloggedTunnel = info;
                        }
                    }
                }
                // return a random backlogged tunnel
                if (backloggedTunnel != null)
                    return backloggedTunnel;
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": after " + _tunnels.size() + " tries, no unexpired ones were found: " + _tunnels);
            }
        }
        
        if (_alive && _settings.getAllowZeroHop())
            buildFallback();
        if (allowRecurseOnFail)
            return selectTunnel(false); 
        else
            return null;
    }
    
    public TunnelInfo getTunnel(TunnelId gatewayId) {
        synchronized (_tunnels) {
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (_settings.isInbound()) {
                    if (info.getReceiveTunnelId(0).equals(gatewayId))
                        return info;
                } else {
                    if (info.getSendTunnelId(0).equals(gatewayId))
                        return info;
                }
            }
        }
        return null;
    }
    
    /**
     * Return a list of tunnels in the pool
     *
     * @return list of TunnelInfo objects
     */
    public List listTunnels() {
        synchronized (_tunnels) {
            return new ArrayList(_tunnels);
        }
    }
    
    /** list of tunnelInfo instances of tunnels currently being built */
    public List listPending() { synchronized (_inProgress) { return new ArrayList(_inProgress); } }
    
    int getTunnelCount() { synchronized (_tunnels) { return _tunnels.size(); } }
    
    public TunnelPoolSettings getSettings() { return _settings; }
    public void setSettings(TunnelPoolSettings settings) { 
        _settings = settings; 
        if (_settings != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": Settings updated on the pool: " + settings);
            _manager.getExecutor().repoll(); // in case we need more
        }
    }
    public TunnelPeerSelector getSelector() { return _peerSelector; }
    public boolean isAlive() { return _alive; }
    public int size() { 
        synchronized (_tunnels) {
            return _tunnels.size();
        }
    }
    
    public void addTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Adding tunnel " + info, new Exception("Creator"));
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.add(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
        }
        
        if (ls != null)
            _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
    }
    
    public void removeTunnel(TunnelInfo info) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": Removing tunnel " + info);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(info);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
            if (_lastSelected == info) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
        }

        _manager.getExecutor().repoll();
            
        _lifetimeProcessed += info.getProcessedMessagesCount();
        updateRate();
        
        long lifetimeConfirmed = info.getVerifiedBytesTransferred();
        long lifetime = 10*60*1000;
        for (int i = 0; i < info.getLength(); i++)
            _context.profileManager().tunnelLifetimePushed(info.getPeer(i), lifetime, lifetimeConfirmed);
        
        if (_alive && _settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(toString() + ": unable to build a new leaseSet on removal (" + remaining 
                              + " remaining), request a new tunnel");
                if (_settings.getAllowZeroHop())
                    buildFallback();
            }
        }
    
        boolean connected = true;
        if ( (_settings.getDestination() != null) && (!_context.clientManager().isLocal(_settings.getDestination())) )
            connected = false;
        if ( (getTunnelCount() <= 0) && (!connected) ) {
            _manager.removeTunnels(_settings.getDestination());
            return;
        }
    }

    public void tunnelFailed(PooledTunnelCreatorConfig cfg) {
        if (_log.shouldLog(Log.WARN))
            _log.warn(toString() + ": Tunnel failed: " + cfg);
        int remaining = 0;
        LeaseSet ls = null;
        synchronized (_tunnels) {
            _tunnels.remove(cfg);
            if (_settings.isInbound() && (_settings.getDestination() != null) )
                ls = locked_buildNewLeaseSet();
            remaining = _tunnels.size();
            if (_lastSelected == cfg) {
                _lastSelected = null;
                _lastSelectionPeriod = 0;
            }
        }
        
        _manager.tunnelFailed();
        tellProfileFailed(cfg);
        
        _lifetimeProcessed += cfg.getProcessedMessagesCount();
        updateRate();
        
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            }
        }
    }

    // Blame all the other peers in the tunnel, with a probability
    // inversely related to the tunnel length
    private void tellProfileFailed(PooledTunnelCreatorConfig cfg) {
        int len = cfg.getLength();
        if (len < 2)
            return;
        int start = 0;
        int end = len;
        if (cfg.isInbound())
            end--;
        else
            start++;
        for (int i = start; i < end; i++) {
            int pct = 100/(len-1);
            // if inbound, it's probably the gateway's fault
            if (cfg.isInbound() && len > 2) {
                if (i == start)
                    pct *= 2;
                else
                    pct /= 2;
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn(toString() + ": Blaming " + cfg.getPeer(i) + ' ' + pct + '%');
            _context.profileManager().tunnelFailed(cfg.getPeer(i), pct);
        }
    }

    void updateRate() {
        long now = _context.clock().now();
        long et = now - _lastRateUpdate;
        if (et > 2*60*1000) {
            long bw = 1024 * (_lifetimeProcessed - _lastLifetimeProcessed) * 1000 / et;   // Bps
            _context.statManager().addRateData(_rateName, bw, 0);
            _lastRateUpdate = now;
            _lastLifetimeProcessed = _lifetimeProcessed;
        }
    }

    void refreshLeaseSet() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(toString() + ": refreshing leaseSet on tunnel expiration (but prior to grace timeout)");
        int remaining = 0;
        LeaseSet ls = null;
        if (_settings.isInbound() && (_settings.getDestination() != null) ) {
            synchronized (_tunnels) {
                ls = locked_buildNewLeaseSet();
                remaining = _tunnels.size();
            }
            if (ls != null) {
                _context.clientManager().requestLeaseSet(_settings.getDestination(), ls);
            }
        }
    }

    /**
     * Return true if a fallback tunnel is built
     *
     */
    boolean buildFallback() {
        int quantity = _settings.getBackupQuantity() + _settings.getQuantity();
        int usable = 0;
        synchronized (_tunnels) {
            usable = _tunnels.size();
        }
        if (usable > 0)
            return false;

        if (_settings.getAllowZeroHop()) {
            if ( (_settings.getLength() + _settings.getLengthVariance() > 0) && 
                 (_settings.getDestination() != null) &&
                 (_context.profileOrganizer().countActivePeers() > 0) ) {
                // if it is a client tunnel pool and our variance doesn't allow 0 hop, prefer failure to
                // 0 hop operation (unless our router is offline)
                return false;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info(toString() + ": building a fallback tunnel (usable: " + usable + " needed: " + quantity + ")");
            
            // runs inline, since its 0hop
            _manager.getExecutor().buildTunnel(this, configureNewTunnel(true));
            return true;
        }
        return false;
    }
    
    /**
     * Build a leaseSet with the required tunnels that aren't about to expire
     *
     */
    private LeaseSet locked_buildNewLeaseSet() {
        if (!_alive)
            return null;
        long expireAfter = _context.clock().now(); // + _settings.getRebuildPeriod();
        
        List leases = new ArrayList(_tunnels.size());
        for (int i = 0; i < _tunnels.size(); i++) {
            TunnelInfo tunnel = (TunnelInfo)_tunnels.get(i);
            if (tunnel.getExpiration() <= expireAfter)
                continue; // expires too soon, skip it
            
            TunnelId inId = tunnel.getReceiveTunnelId(0);
            Hash gw = tunnel.getPeer(0);
            if ( (inId == null) || (gw == null) ) {
                _log.error(toString() + ": wtf, tunnel has no inbound gateway/tunnelId? " + tunnel);
                continue;
            }
            Lease lease = new Lease();
            lease.setEndDate(new Date(tunnel.getExpiration()));
            lease.setTunnelId(inId);
            lease.setGateway(gw);
            leases.add(lease);
        }
        
        int wanted = _settings.getQuantity();
        
        if (leases.size() < wanted) {
            if (_log.shouldLog(Log.WARN))
                _log.warn(toString() + ": Not enough leases (" + leases.size() + ", wanted " + wanted + ")");
            return null;
        } else {
            // linear search to trim down the leaseSet, removing the ones that 
            // will expire the earliest.  cheaper than a tree for this size
            while (leases.size() > wanted) {
                int earliestIndex = -1;
                long earliestExpiration = -1;
                for (int i = 0; i < leases.size(); i++) {
                    Lease cur = (Lease) leases.get(i);
                    if ( (earliestExpiration < 0) || (cur.getEndDate().getTime() < earliestExpiration) ) {
                        earliestIndex = i;
                        earliestExpiration = cur.getEndDate().getTime();
                    }
                }
                leases.remove(earliestIndex);
            }
        }
        LeaseSet ls = new LeaseSet();
        for (int i = 0; i < leases.size(); i++)
             ls.addLease((Lease) leases.get(i));
        if (_log.shouldLog(Log.INFO))
            _log.info(toString() + ": built new leaseSet: " + ls);
        return ls;
    }
    
    public long getLifetimeProcessed() { return _lifetimeProcessed; }
    
    /**
     * Keep a separate stat for each type, direction, and length of tunnel.
     */
    private final String buildRateName() {
        if (_settings.isExploratory())
            return "tunnel.buildRatio.exploratory." + (_settings.isInbound() ? "in" : "out");
        else
            return "tunnel.buildRatio.l" + _settings.getLength() + "v" + _settings.getLengthVariance() +
                    (_settings.isInbound() ? ".in" : ".out");
    }

    /**
     * Gather the data to see how many tunnels to build, and then actually compute that value (delegated to
     * the countHowManyToBuild function below)
     *
     */
    public int countHowManyToBuild() {
        if (_settings.getDestination() != null) {
            if (!_context.clientManager().isLocal(_settings.getDestination()))
                return 0;
        }
        int wanted = getSettings().getBackupQuantity() + getSettings().getQuantity();
        
        boolean allowZeroHop = ((getSettings().getLength() + getSettings().getLengthVariance()) <= 0);
          
        /**
         * This algorithm builds based on the previous average length of time it takes
         * to build a tunnel. This average is kept in the _buildRateName stat.
         * It is a separate stat for each type of pool, since in and out building use different methods,
         * as do exploratory and client pools,
         * and each pool can have separate length and length variance settings.
         * We add one minute to the stat for safety (two for exploratory tunnels).
         *
         * We linearly increase the number of builds per expiring tunnel from
         * 1 to PANIC_FACTOR as the time-to-expire gets shorter.
         *
         * The stat will be 0 for first 10m of uptime so we will use the older, conservative algorithm
         * below instead. This algorithm will take about 30m of uptime to settle down.
         * Or, if we are building more than 33% of the time something is seriously wrong,
         * we also use the conservative algorithm instead
         *
         **/

        // Compute the average time it takes us to build a single tunnel of this type.
        int avg = 0;
        RateStat rs = _context.statManager().getRate(buildRateName());
        if (rs == null) {
            // Create the RateStat here rather than at the top because
            // the user could change the length settings while running
            _context.statManager().createRateStat(buildRateName(),
                                   "Tunnel Build Frequency", "Tunnels",
                                   new long[] { TUNNEL_LIFETIME });
            rs = _context.statManager().getRate(buildRateName());
        }
        if (rs != null) {
            Rate r = rs.getRate(TUNNEL_LIFETIME);
            if (r != null)
                avg = (int) ( TUNNEL_LIFETIME * r.getAverageValue() / wanted);
        }

        if (avg > 0 && avg < TUNNEL_LIFETIME / 3) {  // if we're taking less than 200s per tunnel to build
            final int PANIC_FACTOR = 4;  // how many builds to kick off when time gets short
            avg += 60*1000;   // one minute safety factor
            if (_settings.isExploratory())
                avg += 60*1000;   // two minute safety factor
            long now = _context.clock().now();

            int expireSoon = 0;
            int expireLater = 0;
            int expireTime[];
            int fallback = 0;
            synchronized (_tunnels) {
                expireTime = new int[_tunnels.size()];
                for (int i = 0; i < _tunnels.size(); i++) {
                    TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                    if (allowZeroHop || (info.getLength() > 1)) {
                        int timeToExpire = (int) (info.getExpiration() - now);
                        if (timeToExpire > 0 && timeToExpire < avg) {
                            expireTime[expireSoon++] = timeToExpire;
                        } else {
                            expireLater++;
                        }
                    } else if (info.getExpiration() - now > avg) {
                        fallback++;
                    }
                }
            }

            int inProgress;
            synchronized (_inProgress) {
                inProgress = _inProgress.size();
            }
            int remainingWanted = (wanted - expireLater) - inProgress;
            if (allowZeroHop)
                remainingWanted -= fallback;

            int rv = 0;
            int latesttime = 0;
            if (remainingWanted > 0) {
                if (remainingWanted > expireSoon) {
                    rv = PANIC_FACTOR * (remainingWanted - expireSoon);  // for tunnels completely missing
                    remainingWanted = expireSoon;
                }
                // add from 1 to PANIC_FACTOR builds, depending on how late it is
                // only use the expire times of the latest-expiring tunnels,
                // the other ones are extras
                for (int i = 0; i < remainingWanted; i++) {
                    int latestidx = 0;
                    // given the small size of the array this is efficient enough
                    for (int j = 0; j < expireSoon; j++) {
                        if (expireTime[j] > latesttime) {
                            latesttime = expireTime[j];
                            latestidx = j;
                        }
                    }
                    expireTime[latestidx] = 0;
                    if (latesttime > avg / 2)
                        rv += 1;
                    else
                        rv += 2 + ((PANIC_FACTOR - 2) * (((avg / 2) - latesttime) / (avg / 2)));
                }
            }

            if (rv > 0 && _log.shouldLog(Log.DEBUG))
                _log.debug("New Count: rv: " + rv + " allow? " + allowZeroHop
                       + " avg " + avg + " latesttime " + latesttime
                       + " soon " + expireSoon + " later " + expireLater
                       + " std " + wanted + " inProgress " + inProgress + " fallback " + fallback 
                       + " for " + toString());
            _context.statManager().addRateData(buildRateName(), rv + inProgress, 0);
            return rv;
        }

        // fixed, conservative algorithm - starts building 3 1/2 - 6m before expiration
        // (210 or 270s) + (0..90s random)
        long expireAfter = _context.clock().now() + _expireSkew; // + _settings.getRebuildPeriod() + _expireSkew;
        int expire30s = 0;
        int expire90s = 0;
        int expire150s = 0;
        int expire210s = 0;
        int expire270s = 0;
        int expireLater = 0;
        
        int fallback = 0;
        synchronized (_tunnels) {
            boolean enough = _tunnels.size() > wanted;
            for (int i = 0; i < _tunnels.size(); i++) {
                TunnelInfo info = (TunnelInfo)_tunnels.get(i);
                if (allowZeroHop || (info.getLength() > 1)) {
                    long timeToExpire = info.getExpiration() - expireAfter;
                    if (timeToExpire <= 0) {
                        // consider it unusable
                    } else if (timeToExpire <= 30*1000) {
                        expire30s++;
                    } else if (timeToExpire <= 90*1000) {
                        expire90s++;
                    } else if (timeToExpire <= 150*1000) {
                        expire150s++;
                    } else if (timeToExpire <= 210*1000) {
                        expire210s++;
                    } else if (timeToExpire <= 270*1000) {
                        expire270s++;
                    } else {
                        expireLater++;
                    }
                } else if (info.getExpiration() > expireAfter) {
                    fallback++;
                }
            }
        }
        
        int inProgress = 0;
        synchronized (_inProgress) {
            inProgress = _inProgress.size();
            for (int i = 0; i < _inProgress.size(); i++) {
                PooledTunnelCreatorConfig cfg = (PooledTunnelCreatorConfig)_inProgress.get(i);
                if (cfg.getLength() <= 1)
                    fallback++;
            }
        }
        
        int rv = countHowManyToBuild(allowZeroHop, expire30s, expire90s, expire150s, expire210s, expire270s, 
                                   expireLater, wanted, inProgress, fallback);
        _context.statManager().addRateData(buildRateName(), (rv > 0 || inProgress > 0) ? 1 : 0, 0);
        return rv;

    }
    
    /**
     * Helper function for the old conservative algorithm.
     * This is the big scary function determining how many new tunnels we want to try to build at this
     * point in time, as used by the BuildExecutor
     *
     * @param allowZeroHop do we normally allow zero hop tunnels?  If true, treat fallback tunnels like normal ones
     * @param earliestExpire how soon do some of our usable tunnels expire, or, if we are missing tunnels, -1
     * @param usable how many tunnels will be around for a while (may include fallback tunnels)
     * @param wantToReplace how many tunnels are still usable, but approaching unusability
     * @param standardAmount how many tunnels we want to have, in general
     * @param inProgress how many tunnels are being built for this pool right now (may include fallback tunnels)
     * @param fallback how many zero hop tunnels do we have, or are being built
     */
    private int countHowManyToBuild(boolean allowZeroHop, int expire30s, int expire90s, int expire150s, int expire210s,
                                    int expire270s, int expireLater, int standardAmount, int inProgress, int fallback) {
        int rv = 0;
        int remainingWanted = standardAmount - expireLater;
        if (allowZeroHop)
            remainingWanted -= fallback;

        for (int i = 0; i < expire270s && remainingWanted > 0; i++)
            remainingWanted--;
        if (remainingWanted > 0) {
            // 1x the tunnels expiring between 3.5 and 2.5 minutes from now
            for (int i = 0; i < expire210s && remainingWanted > 0; i++) {
                remainingWanted--;
            }
            if (remainingWanted > 0) {
                // 2x the tunnels expiring between 2.5 and 1.5 minutes from now
                for (int i = 0; i < expire150s && remainingWanted > 0; i++) {
                    remainingWanted--;
                }
                if (remainingWanted > 0) {
                    for (int i = 0; i < expire90s && remainingWanted > 0; i++) {
                        remainingWanted--;
                    }
                    if (remainingWanted > 0) {
                        for (int i = 0; i < expire30s && remainingWanted > 0; i++) {
                            remainingWanted--;
                        }
                        if (remainingWanted > 0) {
                            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                            rv += expire210s;
                            rv += 2*expire150s;
                            rv += 4*expire90s;
                            rv += 6*expire30s;
                            rv += 6*remainingWanted;
                            rv -= inProgress;
                            rv -= expireLater;
                        } else {
                            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                            rv += expire210s;
                            rv += 2*expire150s;
                            rv += 4*expire90s;
                            rv += 6*expire30s;
                            rv -= inProgress;
                            rv -= expireLater;
                        }
                    } else {
                        rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                        rv += expire210s;
                        rv += 2*expire150s;
                        rv += 4*expire90s;
                        rv -= inProgress;
                        rv -= expireLater;
                    }
                } else {
                    rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                    rv += expire210s;
                    rv += 2*expire150s;
                    rv -= inProgress;
                    rv -= expireLater;
                }
            } else {
                rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
                rv += expire210s;
                rv -= inProgress;
                rv -= expireLater;
            }
        } else {
            rv = (((expire270s > 0) && _context.random().nextBoolean()) ? 1 : 0);
            rv -= inProgress;
            rv -= expireLater;
        }
        // yes, the above numbers and periods are completely arbitrary.  suggestions welcome
        
        if (allowZeroHop && (rv > standardAmount))
            rv = standardAmount;
        
        if (rv + inProgress + expireLater + fallback > 4*standardAmount)
            rv = 4*standardAmount - inProgress - expireLater - fallback;
        
        long lifetime = getLifetime();
        if ( (lifetime < 60*1000) && (rv + inProgress + fallback >= standardAmount) )
                rv = standardAmount - inProgress - fallback;
        
        if (rv > 0 && _log.shouldLog(Log.DEBUG))
            _log.debug("Count: rv: " + rv + " allow? " + allowZeroHop
                       + " 30s " + expire30s + " 90s " + expire90s + " 150s " + expire150s + " 210s " + expire210s
                       + " 270s " + expire270s + " later " + expireLater
                       + " std " + standardAmount + " inProgress " + inProgress + " fallback " + fallback 
                       + " for " + toString() + " up for " + lifetime);
        
        if (rv < 0)
            return 0;
        return rv;
    }
    
    PooledTunnelCreatorConfig configureNewTunnel() { return configureNewTunnel(false); }
    private PooledTunnelCreatorConfig configureNewTunnel(boolean forceZeroHop) {
        TunnelPoolSettings settings = getSettings();
        List peers = null;
        long expiration = _context.clock().now() + settings.getDuration();

        if (!forceZeroHop) {
            peers = _peerSelector.selectPeers(_context, settings);
            if ( (peers == null) || (peers.size() <= 0) ) {
                // no inbound or outbound tunnels to send the request through, and 
                // the pool is refusing 0 hop tunnels
                if (peers == null) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No peers to put in the new tunnel! selectPeers returned null!  boo, hiss!");
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("No peers to put in the new tunnel! selectPeers returned an empty list?!");
                }
                return null;
            }
        } else {
            peers = new ArrayList(1);
            peers.add(_context.routerHash());
        }
        PooledTunnelCreatorConfig cfg = new PooledTunnelCreatorConfig(_context, peers.size(), settings.isInbound(), settings.getDestination());
        cfg.setTunnelPool(this);
        // peers[] is ordered endpoint first, but cfg.getPeer() is ordered gateway first
        for (int i = 0; i < peers.size(); i++) {
            int j = peers.size() - 1 - i;
            cfg.setPeer(j, (Hash)peers.get(i));
            HopConfig hop = cfg.getConfig(j);
            hop.setCreation(_context.clock().now());
            hop.setExpiration(expiration);
            hop.setIVKey(_context.keyGenerator().generateSessionKey());
            hop.setLayerKey(_context.keyGenerator().generateSessionKey());
            // tunnelIds will be updated during building, and as the creator, we
            // don't need to worry about prev/next hop
        }
        cfg.setExpiration(expiration);
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Config contains " + peers + ": " + cfg);
        synchronized (_inProgress) {
            _inProgress.add(cfg);
        }
        return cfg;
    }
    
    private List _inProgress = new ArrayList();
    void buildComplete(PooledTunnelCreatorConfig cfg) {
        synchronized (_inProgress) { _inProgress.remove(cfg); }
        cfg.setTunnelPool(this);
        //_manager.buildComplete(cfg);
    }
    
    public String toString() {
        if (_settings.isExploratory()) {
            if (_settings.isInbound())
                return "Inbound exploratory pool";
            else
                return "Outbound exploratory pool";
        } else {
            StringBuffer rv = new StringBuffer(32);
            if (_settings.isInbound())
                rv.append("Inbound client pool for ");
            else
                rv.append("Outbound client pool for ");
            if (_settings.getDestinationNickname() != null)
                rv.append(_settings.getDestinationNickname());
            else
                rv.append(_settings.getDestination().toBase64().substring(0,4));
            return rv.toString();
        }
            
    }
}
