package dev.glassbox.retrieval;

import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * For the parent_child chunking: replaces each retrieved child by its parent, so the prompt gets the surrounding text the child was
 * found in. Ranks and scores stay those of the child; a parent that several children point to appears once, at its best child's place.
 */
@Component
public class ParentExpander {
    private final Tx tx;

    public ParentExpander(Tx tx) {
        this.tx = tx;
    }

    public List<Candidate> expand(UUID workspaceId, List<Candidate> ranked) {
        if (ranked.isEmpty()) return ranked;
        UUID[] ids = ranked.stream().map(Candidate::chunkId).toArray(UUID[]::new);
        Map<UUID, Candidate> parentOf = new HashMap<>();
        tx.workspace(workspaceId, null, j -> {
            j.query("SELECT c.id AS child_id, p.id, p.document_id, p.text, p.token_count, p.char_start, p.char_end, p.page "
                            + "FROM chunks c JOIN chunks p ON p.id = c.parent_chunk_id WHERE c.id = ANY (?)",
                    rs -> {
                        parentOf.put(rs.getObject("child_id", UUID.class), new Candidate(rs.getObject("id", UUID.class),
                                rs.getObject("document_id", UUID.class), rs.getString("text"), rs.getInt("token_count"),
                                rs.getInt("char_start"), rs.getInt("char_end"), (Integer) rs.getObject("page"),
                                0, null, null, null, null, null));
                    }, (Object) ids);
            return null;
        });
        List<Candidate> out = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Candidate c : ranked) {
            Candidate p = parentOf.get(c.chunkId());
            if (p == null) {
                if (seen.add(c.chunkId())) out.add(c);
                continue;
            }
            if (!seen.add(p.chunkId())) continue;
            out.add(new Candidate(p.chunkId(), p.documentId(), p.text(), p.tokenCount(), p.charStart(), p.charEnd(), p.page(),
                    c.fusedScore(), c.vecRank(), c.vecDistance(), c.kwRank(), c.kwScore(), c.rerankScore()));
        }
        return out;
    }
}
