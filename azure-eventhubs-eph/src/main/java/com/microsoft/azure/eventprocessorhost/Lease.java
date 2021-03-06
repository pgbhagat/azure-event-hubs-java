/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.eventprocessorhost;

import java.util.concurrent.CompletableFuture;

/**
 * Lease class is public so that advanced users can implement an ILeaseManager. 
 * Unless you are implementing ILeaseManager you should not have to deal with objects
 * of this class or derived classes directly.
 * 
 * When implementing an ILeaseManager it may be necessary to derive from this class to
 * carry around more information and override isExpired. The data fields have been left
 * private instead of protected because they have a full set of getters and setters
 * (except partitionId, which is immutable) which provide equivalent access. When
 * implementing AzureBlobLease, for example, there was no need for more access than
 * the getters and setters provide.
 * 
 * Note that a Lease object just carries information about a partition lease. The APIs
 * to acquire/renew/release a lease are all on ILeaseManager.
 */
public class Lease
{
    private final String partitionId;

    private long epoch;
    private String owner;
    private String token;

    /**
     * Do not use; added only for GSon deserializer
     */
    protected Lease()
    {
        partitionId = "-1";
    }

    /**
     * Create a Lease for the given partition.
     * 
     * @param partitionId  Partition id for this lease.
     */
    public Lease(String partitionId)
    {
        this.partitionId = partitionId;

        this.epoch = 0;
        this.owner = "";
        this.token = "";
    }

    /**
     * Create a Lease by duplicating the given Lease.
     * 
     * @param source Lease to clone.
     */
    public Lease(Lease source)
    {
        this.partitionId = source.partitionId;

        this.epoch = source.epoch;
        this.owner = source.owner;
        this.token = source.token;
    }

    /**
     * Epoch is a concept used by Event Hub receivers. If a receiver is created on a partition
     * with a higher epoch than the existing receiver, the previous receiver is forcibly disconnected.
     * Attempting to create a receiver with a lower epoch that the existing receiver will fail. The Lease
     * carries the epoch around so that when a host instance steals a lease, it can create a receiver with a higher epoch.
     *  
     * @return the epoch of the current receiver
     */
    public long getEpoch()
    {
        return this.epoch;
    }

    /**
     * Set the epoch value. Used to update the lease after creating a new receiver with a higher epoch.
     * 
     * @param epoch  updated epoch value
     */
    public void setEpoch(long epoch)
    {
        this.epoch = epoch;
    }
    
    /**
     * The most common operation on the epoch value is incrementing it after stealing a lease. This
     * convenience function replaces the get-increment-set that would otherwise be required.
     * 
     * @return The new value of the epoch.
     */
    public long incrementEpoch()
    {
    	this.epoch++;
    	return this.epoch;
    }
    
    /**
     * The owner of a lease is the name of the EventProcessorHost instance which currently holds the lease.
     * 
     * @return  name of the owning instance
     */
    public String getOwner()
    {
        return this.owner;
    }

    /**
     * Set the owner string. Used when a host steals a lease.
     * 
     * @param owner  name of the new owning instance
     */
    public void setOwner(String owner)
    {
        this.owner = owner;
    }

    /**
     * Convenience function for comparing possibleOwner against this.owner
     * 
     * @param possibleOwner  name to check 
     * @return  true if possibleOwner is the same as this.owner, false otherwise
     */
    public boolean isOwnedBy(String possibleOwner)
    {
    	boolean retval = false;
    	if (this.owner != null)
    	{
        	retval = (this.owner.compareTo(possibleOwner) == 0);
    	}
    	return retval;
    }

    /**
     * Returns the id of the partition that this Lease is for. Immutable so there is no corresponding setter.
     * 
     * @return partition id
     */
    public String getPartitionId()
    {
        return this.partitionId;
    }

    /**
     * The "token" is an arbitrary string whose use is not defined by Lease. AzureStorageCheckpointLeaseManager uses the token to
     * store the blob lease ID used by the Azure Storage API. Other implementations of ILeaseManager may use it
     * for anything.
     * 
     * @return the current token
     */
    public String getToken()
    {
        return this.token;
    }

    /**
     * Set the token value.
     * 
     * @param token  new value for the token
     */
    public void setToken(String token)
    {
        this.token = token;
    }

    /**
     * A class derived from Lease should override this function to inspect the lease and return whether it has expired.
     * Uses CompletableFuture because determining whether a lease is expired may involve I/O.
     *  
     * @return CompletableFuture {@literal ->} true if the lease is expired, false if it is still valid, completes exceptionally on error.
     */
    public CompletableFuture<Boolean> isExpired()
    {
    	// this function is meaningless in the base class
    	return CompletableFuture.completedFuture(false);
    }
    
    String getStateDebug()
    {
    	return "N/A";
    }
}
