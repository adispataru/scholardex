package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Membership;
import ro.uvt.pokedex.core.model.org.MembershipRole;
import ro.uvt.pokedex.core.repository.org.MembershipRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Group ⇄ user membership operations. Replaces the legacy {@code Group.memberIds}
 * field. Only "current" memberships (validTo == null) are exposed by the standard
 * lookups; historical filtering belongs in report-runner code that knows the window.
 */
@Service
@RequiredArgsConstructor
public class GroupMembershipService {
    private final MembershipRepository membershipRepository;

    public List<String> listCurrentMemberUserIds(String groupId) {
        if (groupId == null || groupId.isBlank()) return List.of();
        return membershipRepository.findByGroupIdAndValidToIsNull(groupId).stream()
                .map(Membership::getUserId)
                .toList();
    }

    public List<Membership> listCurrentMemberships(String groupId) {
        if (groupId == null || groupId.isBlank()) return List.of();
        return membershipRepository.findByGroupIdAndValidToIsNull(groupId);
    }

    public int addMembers(String groupId, Collection<String> userIds) {
        if (groupId == null || groupId.isBlank() || userIds == null || userIds.isEmpty()) return 0;
        List<Membership> toCreate = new ArrayList<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) continue;
            Optional<Membership> existing = membershipRepository
                    .findByGroupIdAndUserIdAndValidToIsNull(groupId, userId);
            if (existing.isPresent()) continue;
            toCreate.add(newMembership(groupId, userId, MembershipRole.MEMBER));
        }
        if (toCreate.isEmpty()) return 0;
        membershipRepository.saveAll(toCreate);
        return toCreate.size();
    }

    /**
     * Aligns current memberships of {@code groupId} with {@code desiredUserIds}: new entries
     * become MEMBER memberships; entries no longer in the desired set are soft-closed
     * ({@code validTo=today}). Order-preserving; existing memberships untouched.
     *
     * @return summary of additions and removals
     */
    public SyncResult syncMembers(String groupId, Collection<String> desiredUserIds) {
        if (groupId == null || groupId.isBlank()) return new SyncResult(0, 0);
        Set<String> desired = new HashSet<>();
        if (desiredUserIds != null) {
            for (String userId : desiredUserIds) {
                if (userId != null && !userId.isBlank()) desired.add(userId);
            }
        }
        List<Membership> current = membershipRepository.findByGroupIdAndValidToIsNull(groupId);
        Set<String> currentUserIds = new HashSet<>();
        List<Membership> toClose = new ArrayList<>();
        for (Membership membership : current) {
            currentUserIds.add(membership.getUserId());
            if (!desired.contains(membership.getUserId())) {
                membership.setValidTo(LocalDate.now());
                toClose.add(membership);
            }
        }
        if (!toClose.isEmpty()) {
            membershipRepository.saveAll(toClose);
        }
        List<String> toAdd = new ArrayList<>();
        for (String userId : desired) {
            if (!currentUserIds.contains(userId)) toAdd.add(userId);
        }
        int added = addMembers(groupId, toAdd);
        return new SyncResult(added, toClose.size());
    }

    public boolean removeMember(String groupId, String userId) {
        Optional<Membership> current = membershipRepository
                .findByGroupIdAndUserIdAndValidToIsNull(groupId, userId);
        if (current.isEmpty()) return false;
        Membership membership = current.get();
        membership.setValidTo(LocalDate.now());
        membershipRepository.save(membership);
        return true;
    }

    public record SyncResult(int added, int removed) {}

    private Membership newMembership(String groupId, String userId, MembershipRole role) {
        Membership m = new Membership();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        m.setValidFrom(LocalDate.now());
        m.setCreatedAt(Instant.now());
        return m;
    }
}
