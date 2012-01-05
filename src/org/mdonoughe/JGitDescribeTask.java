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
import org.apache.tools.ant.Task;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.FollowFilter;

/**
 * Ant task to emulate git-describe using jgit.
 */
public class JGitDescribeTask extends Task {

    /** Path to .git Directory. */
    private File dir;

    /** Length of sha1 to use in output. */
    private int shalength;

    /** What reference to use as a starting point for the walk. */
    private String ref;

    /** What property to store the output in. */
    private String property;

    /** Check specifically for the last commit in this subdir. */
    private String subdir;

    /**
     * Set the .git directory
     *
     * @param path
     */
    public void setDir(final File path) {
        dir = path;
    }

    /**
     * Set the sha1 length
     *
     * @param length New value
     */
    public void setShalength(final int length) {
        shalength = length;
    }

    /**
     * Set the reference to use as a starting point for the walk.
     *
     * @param newRef New value
     */
    public void setRef(final String newRef) {
        ref = newRef;
    }

    /**
     * Set the property to store the output in
     *
     * @param oproperty New value
     */
    public void setProperty(final String oproperty) {
        property = oproperty;
    }

    /**
     * Set the subdir to look at
     *
     * @param newSubDir New value
     */
    public void setSubDir(final String newSubDir) {
        subdir = newSubDir;
    }

    /**
     * Create a new instance of JGitDescribeTask
     */
    public JGitDescribeTask() {
        dir = new File(".git");
        shalength = 7;
        ref = "HEAD";
    }

    /**
     * Get a Revision Walker instance set up with the correct tree filter.
     *
     * @param repository Repository that should be walked.
     * @return RevWalker instance with Tree Filter if required.
     * @throws BuildException If the given subdir is invalid.
     */
    public RevWalk getWalk(final Repository repository) throws BuildException {
        RevWalk walk = null;
        walk = new RevWalk(repository);

        if (subdir != null) {
            final String parent = repository.getDirectory().getParent() + "/";
            subdir = subdir.replaceFirst("^" + Pattern.quote(parent), "");
            if (!new File(parent + subdir).exists()) {
                throw new BuildException("'"+subdir+"' does not appear to be a subdir of this repo.");
            }
            walk.setTreeFilter(FollowFilter.create(subdir));
        }

        return walk;
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws BuildException {
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
            walk = getWalk(repository);

            start = walk.parseCommit(repository.resolve(ref));
            walk.markStart(start);
            if (subdir != null) {
                // final RevWalk subWalk = getWalk(repository);
                final RevCommit next = walk.next();
                if (next != null) {
                    walk = getWalk(repository);
                    start = walk.parseCommit(next);
                }
            }
        } catch (IOException e) {
            throw new BuildException("Could not find target", e);
        }

        final Map<ObjectId, String> tags = new HashMap<ObjectId,String>();

        for (Map.Entry<String, Ref> tag : repository.getTags().entrySet()) {
            try {
                RevTag r = walk.parseTag(tag.getValue().getObjectId());
                ObjectId taggedCommit = r.getObject().getId();
                tags.put(taggedCommit, tag.getKey());
            } catch (IOException e) {
                // Theres really no need to panic yet.
            }
        }

        // No tags found. Panic.
        if (tags.isEmpty()) {
            throw new BuildException("No tags found.");
        }

        final List<RevCommit> taggedParents = taggedParentCommits(walk, start, tags);
        RevCommit best = null;
        int bestDistance = 0;
        for (RevCommit commit : taggedParents) {
            int distance = distanceBetween(start, commit);
            if (best == null || (distance < bestDistance)) {
                best = commit;
                bestDistance = distance;
            }
        }

        final StringBuilder sb = new StringBuilder();
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

    /**
     * This does something. I think it gets every possible parent tag this
     * commit has, then later we look for which is closest and use that as
     * the tag to describe. Or something like that.
     *
     * @param walk
     * @param child
     * @param tagmap
     * @return
     * @throws BuildException
     */
    private List<RevCommit> taggedParentCommits(final RevWalk walk, final RevCommit child, final Map<ObjectId, String> tagmap) throws BuildException {
        final Queue<RevCommit> q = new LinkedList<RevCommit>();
        q.add(child);
        final List<RevCommit> taggedcommits = new LinkedList<RevCommit>();
        final Set<ObjectId> seen = new HashSet<ObjectId>();

        while (q.size() > 0) {
            final RevCommit commit = q.remove();
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

    /**
     * Calculate the distance between 2 given commits, parent and child.
     *
     * @param child Commit to calculate distance to (The latest commit)
     * @param parent Commit to calculate distance from (The last tag)
     * @return Numeric value between the 2 commits.
     */
    private int distanceBetween(final RevCommit child, final RevCommit parent) {
        final Set<ObjectId> seen = new HashSet<ObjectId>();
        final Queue<RevCommit> q1 = new LinkedList<RevCommit>();
        final Queue<RevCommit> q2 = new LinkedList<RevCommit>();

        q1.add(child);
        int distance = 1;
        while ((q1.size() > 0) || (q2.size() > 0)) {
            if (q1.size() == 0) {
                distance++;
                q1.addAll(q2);
                q2.clear();
            }
            final RevCommit commit = q1.remove();
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
