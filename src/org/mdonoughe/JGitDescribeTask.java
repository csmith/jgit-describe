import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.RepositoryBuilder;

public class JGitDescribeTask extends Task {
    
    private File dir;
    private int shalength;
    private String target;
    private String property;

    public void setDir (File path) {
        dir = path;
    }

    public void setShalength (int length) {
        shalength = length;
    }

    public void setTarget (String description) {
        target = description;
    }

    public void setProperty (String oproperty) {
        property = oproperty;
    }

    public JGitDescribeTask () {
        dir = new File(".git");
        shalength = 7;
        target = "HEAD";
    }
    
    public void execute () throws BuildException {
        if (property == null) {
            throw new BuildException("\"property\" attribute must be set!");
        }
        
        if (!dir.exists()) {
            throw new BuildException("directory " + dir + " does not exist");
        }
        
        Repository repository = null;
        try {
            RepositoryBuilder builder = new RepositoryBuilder();
            repository = builder.setGitDir(dir).build();
        } catch(IOException e) {
            throw new BuildException("Could not open repository", e);
        }
        
        RevWalk walk = null;
        RevCommit start = null;
        try {
            walk = new RevWalk(repository);
            start = walk.parseCommit(repository.resolve(target));
            walk.markStart(start);
        } catch (IOException e) {
            throw new BuildException("Could not find target", e);
        }
        
        Map<ObjectId, String> tags = new HashMap<ObjectId,String>();
        
        for (Map.Entry<String, Ref> tag : repository.getTags().entrySet()) {
            try {
                RevTag r = walk.parseTag(tag.getValue().getObjectId());
                ObjectId taggedCommit = r.getObject().getId();
                tags.put(taggedCommit, tag.getKey());
            } catch (IOException e) {
                throw new BuildException("Tag not found", e);
            }
        }
        List<RevCommit> taggedParents = taggedParentCommits(walk, start, tags);
        RevCommit best = null;
        int bestDistance = 0;
        for (RevCommit commit : taggedParents) {
            int distance = distanceBetween(start, commit);
            if (best == null || (distance < bestDistance)) {
                best = commit;
                bestDistance = distance;
            }
        }
        
        StringBuilder sb = new StringBuilder();
        if (best != null) {
            sb.append(tags.get(best.getId()));
            sb.append("-");
            sb.append(bestDistance);
            sb.append("-g");
        }
        sb.append(start.getId().abbreviate(shalength).name());
        
        getProject().setProperty(property, sb.toString());
    }
    
    private List<RevCommit> taggedParentCommits (RevWalk walk, 
                                                 RevCommit child, 
                                                 Map<ObjectId, String> tagmap) throws BuildException {
        Queue<RevCommit> q = new LinkedList<RevCommit>();
        q.add(child);
        List<RevCommit> taggedcommits = new LinkedList<RevCommit>();
        Set<ObjectId> seen = new HashSet<ObjectId>();

        while(q.size() > 0) {
            RevCommit commit = q.remove();
            if (tagmap.containsKey(commit.getId())) {
                taggedcommits.add(commit);
                // don't consider commits that are farther away than this tag
                continue;
            }
            for (RevCommit p : commit.getParents()) {
                if (!seen.contains(p.getId())) {
                    seen.add(p.getId());
                    try {
                        q.add(walk.parseCommit(p.getId()));
                    } catch (IOException e) {
                        throw new BuildException("Parent not found", e);
                    }
                }
            }
        }
        return taggedcommits;
    }
    
    private static int distanceBetween (RevCommit child, RevCommit parent) {
        int distance = 0;
        Set<RevCommit> seena = new HashSet<RevCommit>();
        Set<RevCommit> seenb = new HashSet<RevCommit>();
        Queue<RevCommit> q = new LinkedList<RevCommit>();
        q.add(child);
        while (q.size() > 0) {
            RevCommit commit = q.remove();
            if (seena.contains(commit)) {
                continue;
            }
            seena.add(commit);
            if (parent.equals(commit)) {
                // don't consider commits that are included in this commit
                Queue<RevCommit> pq = new LinkedList<RevCommit>();
            	pq.add(commit);
                while (pq.size() > 0) {
                    for (RevCommit pp : pq.remove().getParents()) {
                        if (!seenb.contains(pp)) {
                        	seenb.add(pp);
                        	pq.add(pp);
                        }
                    }
                }
                // remove things we shouldn't have included
                for (RevCommit b : seenb) {
                    if (seena.contains(b)) {
                        distance--;
                    }
                }
                seena.addAll(seenb);
                continue;
            }
            for (RevCommit p : commit.getParents()) {
                if (!seena.contains(p)) {
                    q.add(p);
                }
            }
            distance++;
        }
        return distance;
    }
}