package net.i2p.util;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.time.Timestamper;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 */
public class Clock implements Timestamper.UpdateListener {
    private I2PAppContext _context;
    private Timestamper _timestamper;
    private long _startedOn;
    private boolean _statCreated;
    
    public Clock(I2PAppContext context) {
        _context = context;
        _offset = 0;
        _alreadyChanged = false;
        _listeners = new HashSet(64);
        _timestamper = new Timestamper(context, this);
        _startedOn = System.currentTimeMillis();
        _statCreated = false;
    }
    public static Clock getInstance() {
        return I2PAppContext.getGlobalContext().clock();
    }
    
    public Timestamper getTimestamper() { return _timestamper; }
    
    /** we fetch it on demand to avoid circular dependencies (logging uses the clock) */
    private Log getLog() { return _context.logManager().getLog(Clock.class); }
    
    private volatile long _offset;
    private boolean _alreadyChanged;
    private Set _listeners;

    /** if the clock is skewed by 3+ days, fuck 'em */
    public final static long MAX_OFFSET = 3 * 24 * 60 * 60 * 1000;
    /** after we've started up and shifted the clock, don't allow shifts of more than 10 minutes */
    public final static long MAX_LIVE_OFFSET = 10 * 60 * 1000;
    /** if the clock skewed changes by less than 1s, ignore the update (so we don't slide all over the place) */
    public final static long MIN_OFFSET_CHANGE = 10 * 1000;

    public void setOffset(long offsetMs) {
        setOffset(offsetMs, false);        
    }
    
    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that we are slow, while a negative value means we are fast.
     *
     */
    public void setOffset(long offsetMs, boolean force) {
        long delta = offsetMs - _offset;
        if (!force) {
            if ((offsetMs > MAX_OFFSET) || (offsetMs < 0 - MAX_OFFSET)) {
                getLog().error("Maximum offset shift exceeded [" + offsetMs + "], NOT HONORING IT");
                return;
            }
            
            // only allow substantial modifications before the first 10 minutes
            if (_alreadyChanged && (System.currentTimeMillis() - _startedOn > 10 * 60 * 1000)) {
                if ( (delta > MAX_LIVE_OFFSET) || (delta < 0 - MAX_LIVE_OFFSET) ) {
                    getLog().log(Log.CRIT, "The clock has already been updated, but you want to change it by "
                                           + delta + " to " + offsetMs + "?  Did something break?");
                    return;
                }
            }
            
            if ((delta < MIN_OFFSET_CHANGE) && (delta > 0 - MIN_OFFSET_CHANGE)) {
                getLog().debug("Not changing offset since it is only " + delta + "ms");
                return;
            }
        }
        if (_alreadyChanged) {
            getLog().log(Log.CRIT, "Updating clock offset to " + offsetMs + "ms from " + _offset + "ms");
            if (!_statCreated)
                _context.statManager().createRateStat("clock.skew", "How far is the already adjusted clock being skewed?", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*60 });
                _statCreated = true;
            _context.statManager().addRateData("clock.skew", delta, 0);
        } else {
            getLog().log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms from " + _offset + "ms");
        }
        _alreadyChanged = true;
        _offset = offsetMs;
        fireOffsetChanged(delta);
    }

    public long getOffset() {
        return _offset;
    }

    public void setNow(long realTime) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff);
    }

    /**
     * Retrieve the current time synchronized with whatever reference clock is in
     * use.
     *
     */
    public long now() {
        return _offset + System.currentTimeMillis();
    }

    public void addUpdateListener(ClockUpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.add(lsnr);
        }
    }

    public void removeUpdateListener(ClockUpdateListener lsnr) {
        synchronized (_listeners) {
            _listeners.remove(lsnr);
        }
    }

    private void fireOffsetChanged(long delta) {
        synchronized (_listeners) {
            for (Iterator iter = _listeners.iterator(); iter.hasNext();) {
                ClockUpdateListener lsnr = (ClockUpdateListener) iter.next();
                lsnr.offsetChanged(delta);
            }
        }
    }

    public static interface ClockUpdateListener {
        public void offsetChanged(long delta);
    }
}