/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.cli;

import com.milaboratory.mixcr.util.MiXCRVersionInfo;
import com.milaboratory.util.TimeUtils;
import gnu.trove.map.hash.TIntObjectHashMap;
import io.repseq.core.Chains;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class Util {
    private Util() {
    }

    public static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("#.##");

    public static Chains parseLoci(String lociString) {
        String[] split = lociString.split(",");
        Chains chains = new Chains();
        for (String s : split)
            chains = chains.merge(parseLocus(s));
        return chains;
    }

    private static Chains parseLocus(String value) {
        switch (value.toLowerCase().trim()) {
            case "tcr":
                return Chains.TCR;
            case "ig":
                return Chains.IG;
            case "all":
                return Chains.ALL;
        }
        return new Chains(value);
    }

    public static void writeReport(String input, String output,
                                   String commandLineArguments,
                                   String reportFileName,
                                   ReportWriter reportWriter) {
        writeReport(input, output, commandLineArguments, reportFileName, -1, reportWriter);
    }

    public static void writeReportToStdout(long milliseconds, ReportWriter... reportWriters) {
        ReportHelper helper = new ReportHelper(System.out);

        if (milliseconds != -1)
            helper.writeField("Analysis time", TimeUtils.nanoTimeToString(milliseconds * 1000_000));

        for (ReportWriter wr : reportWriters)
            wr.writeReport(helper);

    }

    public static void writeReport(String input, String output,
                                   String commandLineArguments,
                                   String reportFileName,
                                   long milliseconds, ReportWriter... reportWriters) {
        File file = new File(reportFileName);
        boolean newFile = !file.exists();
        try (FileOutputStream outputStream = new FileOutputStream(file, !newFile)) {
            ReportHelper helper = new ReportHelper(outputStream);
            helper.writeField("Analysis Date", new Date())
                    .writeField("Input file(s)", input)
                    .writeField("Output file", output)
                    .writeField("Version", MiXCRVersionInfo.get().getShortestVersionString());

            if (milliseconds != -1)
                helper.writeField("Analysis time", TimeUtils.nanoTimeToString(milliseconds * 1000_000));

            if (commandLineArguments != null)
                helper.writeField("Command line arguments", commandLineArguments);

            for (ReportWriter wrt : reportWriters)
                wrt.writeReport(helper);

            helper.end();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }


    public static String printTwoColumns(List<String> left, List<String> right, int leftWidth, int rightWidth, int sep) {
        return printTwoColumns(left, right, leftWidth, rightWidth, sep, "");
    }

    public static String printTwoColumns(List<String> left, List<String> right, int leftWidth, int rightWidth, int sep, String betweenLines) {
        return printTwoColumns(0, left, right, leftWidth, rightWidth, sep, betweenLines);
    }

    public static String printTwoColumns(int offset, List<String> left, List<String> right, int leftWidth, int rightWidth, int sep, String betweenLines) {
        if (left.size() != right.size())
            throw new IllegalArgumentException();
        left = new ArrayList<>(left);
        right = new ArrayList<>(right);
        boolean breakOnNext;
        String spacer = spacer(sep), offsetSpacer = spacer(offset);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < left.size(); ++i) {
            String le = left.get(i), ri = right.get(i);
            breakOnNext = true;
            if (le.length() >= leftWidth && ri.length() >= rightWidth) {
                int leBr = lineBreakPos(le, leftWidth), riBr = lineBreakPos(ri, rightWidth);
                String l = le.substring(0, leBr), r = right.get(i).substring(0, riBr);
                String l1 = le.substring(leBr), r1 = right.get(i).substring(riBr);
                le = l;
                ri = r;
                left.add(i + 1, l1);
                right.add(i + 1, r1);
            } else if (le.length() >= leftWidth) {
                int leBr = lineBreakPos(le, leftWidth);
                String l = le.substring(0, leBr), l1 = le.substring(leBr);
                le = l;
                left.add(i + 1, l1);
                right.add(i + 1, "");
            } else if (ri.length() >= rightWidth) {
                int riBr = lineBreakPos(ri, rightWidth);
                String r = ri.substring(0, riBr), r1 = ri.substring(riBr);
                ri = r;
                right.add(i + 1, r1);
                left.add(i + 1, "");
            } else breakOnNext = false;
            sb.append(offsetSpacer).append(le).append(spacer)
                    .append(spacer(leftWidth - le.length())).append(ri).append('\n');
            if (!breakOnNext)
                sb.append(betweenLines);
        }
        assert left.size() == right.size();
        return sb.toString();
    }

    private static TIntObjectHashMap<String> spacesCache = new TIntObjectHashMap<>();

    public static synchronized String spacer(int sep) {
        String s = spacesCache.get(sep);
        if (s == null) {
            StringBuilder sb = new StringBuilder(sep);
            for (int i = 0; i < sep; ++i)
                sb.append(" ");
            spacesCache.put(sep, s = sb.toString());
        }
        return s;
    }

    private static int lineBreakPos(String str, int width) {
        int i = width - 1;
        for (; i >= 0; --i)
            if (str.charAt(i) == ' ')
                break;
        if (i <= 3)
            return width - 1;
        return i + 1;
    }

    public static Path getGlobalSettingsDir() {
        return null;
    }

    public static Path getLocalSettingsDir() {
        return Paths.get(System.getProperty("user.home"), ".mixcr");
    }
}
