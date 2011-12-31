package org.mdonoughe;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import java.util.regex.Pattern;
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
import org.eclipse.jgit.revwalk.FollowFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

public class JGitDescribeTask extends Task {

    private File dir;
    private int shalength;
    private String target;
    private String property;
    private String subdir;

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

    public void setSubDir (String oproperty) {
        subdir = oproperty;
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
            if (subdir != null) {
                final String parent = repository.getDirectory().getParent() + "/";
                subdir = subdir.replaceFirst("^" + Pattern.quote(parent), "");
                if (!new File(parent + subdir).exists()) {
                    throw new BuildException("'"+subdir+"' does not appear to be a subdir of this repo.");
                }
                walk.setTreeFilter(FollowFilter.create(subdir));
            }
            start = walk.parseCommit(repository.resolve(target));
            walk.markStart(start);
            if (subdir != null) {
                start = walk.next();
                walk = new RevWalk(repository);
                start = walk.parseCommit(start);
            }
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
            if (bestDistance > 0) {
                sb.append("-");
                sb.append(bestDistance);
                sb.append("-g");
            }
        }
        if (bestDistance > 0) {
            sb.append(start.getId().abbreviate(shalength).name());
        }

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

    private int distanceBetween (RevCommit child, RevCommit parent) {
        Set<ObjectId> seen = new HashSet<ObjectId>();
        Queue<RevCommit> q1 = new LinkedList<RevCommit>();
        q1.add(child);
        Queue<RevCommit> q2 = new LinkedList<RevCommit>();
        int distance = 1;
        while ((q1.size() > 0) || (q2.size() > 0)) {
            if (q1.size() == 0) {
                distance += 1;
                q1.addAll(q2);
                q2.clear();
            }
            RevCommit commit = q1.remove();
            if (commit.getParents() == null) {
                return 0;
            } else {
                for (RevCommit p : commit.getParents()) {
                    if (p.getId().equals(parent.getId())) {
                        return distance;
                    }
                    if (!seen.contains(p.getId())) {
                        q2.add(p);
                    }
                }
            }
            seen.add(commit.getId());
        }
        return distance;
    }
}
