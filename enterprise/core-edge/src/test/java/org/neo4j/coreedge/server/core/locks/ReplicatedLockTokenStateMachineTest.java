/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.server.core.locks;

import org.junit.Rule;
import org.junit.Test;

import java.util.function.Supplier;

import org.neo4j.coreedge.raft.state.DurableStateStorage;
import org.neo4j.coreedge.raft.state.InMemoryStateStorage;
import org.neo4j.coreedge.raft.state.StateMarshal;
import org.neo4j.coreedge.raft.state.StateStorage;
import org.neo4j.coreedge.server.RaftTestMember;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.rule.TargetDirectory;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.coreedge.server.RaftTestMember.member;

public class ReplicatedLockTokenStateMachineTest
{
    @Test
    public void shouldStartWithInvalidTokenId() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember> stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );

        // when
        int initialTokenId = stateMachine.currentToken().id();

        // then
        assertEquals( initialTokenId, LockToken.INVALID_LOCK_TOKEN_ID );
    }

    @Test
    public void shouldIssueNextLockTokenCandidateId() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember> stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );
        int firstCandidateId = LockToken.nextCandidateId( stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 0 );

        // then
        assertEquals( firstCandidateId + 1, LockToken.nextCandidateId( stateMachine.currentToken().id() ) );
    }

    @Test
    public void shouldKeepTrackOfCurrentLockTokenId() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember>stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );
        int firstCandidateId = LockToken.nextCandidateId( stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 1 );

        // then
        assertEquals( firstCandidateId, stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId + 1 ), 2 );

        // then
        assertEquals( firstCandidateId + 1, stateMachine.currentToken().id() );
    }

    @Test
    public void shouldKeepTrackOfLockTokenOwner() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember>stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );
        int firstCandidateId = LockToken.nextCandidateId( stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 1 );

        // then
        assertEquals( member( 0 ), stateMachine.currentToken().owner() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 1 ), firstCandidateId + 1 ), 2 );

        // then
        assertEquals( member( 1 ), stateMachine.currentToken().owner() );
    }

    @Test
    public void shouldAcceptOnlyFirstRequestWithSameId() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember>stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );
        int firstCandidateId = LockToken.nextCandidateId( stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 1 );
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 1 ), firstCandidateId ), 2 );

        // then
        assertEquals( 0, stateMachine.currentToken().id() );
        assertEquals( member( 0 ), stateMachine.currentToken().owner() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 1 ), firstCandidateId + 1 ), 3 );
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId + 1 ), 4 );

        // then
        assertEquals( 1, stateMachine.currentToken().id() );
        assertEquals( member( 1 ), stateMachine.currentToken().owner() );
    }

    @Test
    public void shouldOnlyAcceptNextImmediateId() throws Exception
    {
        // given
        ReplicatedLockTokenStateMachine<RaftTestMember>stateMachine = new ReplicatedLockTokenStateMachine<>(
                new InMemoryStateStorage<>( new ReplicatedLockTokenState<>() ) );
        int firstCandidateId = LockToken.nextCandidateId( stateMachine.currentToken().id() );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId + 1 ), 1 ); // not accepted

        // then
        assertEquals( stateMachine.currentToken().id(), LockToken.INVALID_LOCK_TOKEN_ID );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 2 ); // accepted

        // then
        assertEquals( stateMachine.currentToken().id(), firstCandidateId );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId + 1 ), 3 ); // accepted

        // then
        assertEquals( stateMachine.currentToken().id(), firstCandidateId + 1 );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId ), 4 ); // not accepted

        // then
        assertEquals( stateMachine.currentToken().id(), firstCandidateId + 1 );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( member( 0 ), firstCandidateId + 3 ), 5 ); // not accepted

        // then
        assertEquals( stateMachine.currentToken().id(), firstCandidateId + 1 );
    }

    @Rule
    public TargetDirectory.TestDirectory testDir = TargetDirectory.testDirForTest( getClass() );

    @Test
    public void shouldPersistAndRecoverState() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        StateMarshal<ReplicatedLockTokenState<RaftTestMember>> marshal = new ReplicatedLockTokenState.Marshal<>( new RaftTestMember.RaftTestMemberMarshal() );

        DurableStateStorage<ReplicatedLockTokenState<RaftTestMember>> storage = new DurableStateStorage<>( fsa, testDir.directory(),
                "state", marshal, 100, health(), NullLogProvider.getInstance() );

        ReplicatedLockTokenStateMachine<RaftTestMember> stateMachine = new ReplicatedLockTokenStateMachine<>( storage );

        RaftTestMember memberA = new RaftTestMember( 0 );
        RaftTestMember memberB = new RaftTestMember( 1 );

        // when
        int candidateId;

        candidateId = 0;
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( memberA, candidateId ), 0 );
        candidateId = 1;
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( memberB, candidateId ), 1 );

        stateMachine.flush();
        fsa.crash();

        // then
        DurableStateStorage<ReplicatedLockTokenState<RaftTestMember>> storage2 = new DurableStateStorage<>(
                fsa, testDir.directory(), "state", marshal, 100,
                health(), NullLogProvider.getInstance() );

        ReplicatedLockTokenState<RaftTestMember> initialState = storage2.getInitialState();

        assertEquals( memberB, initialState.get().owner() );
        assertEquals( candidateId, initialState.get().id() );
    }

    @Test
    public void shouldBeIdempotent() throws Exception
    {
        // given
        EphemeralFileSystemAbstraction fsa = new EphemeralFileSystemAbstraction();
        fsa.mkdir( testDir.directory() );

        StateMarshal<ReplicatedLockTokenState<RaftTestMember>> marshal = new ReplicatedLockTokenState.Marshal<>( new RaftTestMember.RaftTestMemberMarshal() );

        DurableStateStorage<ReplicatedLockTokenState<RaftTestMember>> storage = new DurableStateStorage<>( fsa, testDir.directory(),
                "state", marshal, 100, health(), NullLogProvider.getInstance() );

        ReplicatedLockTokenStateMachine<RaftTestMember>stateMachine = new ReplicatedLockTokenStateMachine<>( storage );

        RaftTestMember memberA = new RaftTestMember( 0 );
        RaftTestMember memberB = new RaftTestMember( 1 );

        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( memberA, 0 ), 3 );

        // when
        stateMachine.applyCommand( new ReplicatedLockTokenRequest<>( memberB, 1 ), 2 );

        // then
        assertEquals( memberA, stateMachine.currentToken().owner() );
    }

    @Test
    public void shouldSetInitialPendingRequestToInitialState() throws Exception
    {
        // Given
        StateStorage<ReplicatedLockTokenState<Object>> storage = mock( StateStorage.class );
        RaftTestMember initialHoldingRaftTestMember = new RaftTestMember( 0 );
        ReplicatedLockTokenState<Object> initialState = new ReplicatedLockTokenState<>( 123, 3, initialHoldingRaftTestMember );
        when( storage.getInitialState() ).thenReturn( initialState );

        // When
        ReplicatedLockTokenStateMachine<Object> stateMachine = new ReplicatedLockTokenStateMachine<>( storage );

        // Then
        LockToken initialToken = stateMachine.currentToken();
        assertEquals( initialState.get().owner(), initialToken.owner() );
        assertEquals( initialState.get().id(), initialToken.id() );
    }

    private Supplier<DatabaseHealth> health()
    {
        return mock( Supplier.class );
    }
}
