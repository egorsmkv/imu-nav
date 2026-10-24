import java.io.File;
import kotlin.Unit;
import org.imunav.core.search.*;
import org.imunav.core.geo.GeoPoint;
import org.imunav.routing.JdbcSearchDb;

/** Checks exact first hits for public Ukraine addresses that previously lost a spaced suffix. */
public class VerifyUkraineHouseSuffix {
    public static void main(String[] args) throws Exception {
        String[][] cases = {
            {"Львів", "Топольна вулиця", "4 в"},
            {"Херсон", "Університетська вулиця", "181 а"},
            {"Дніпро", "вулиця Леоніда Каденюка", "1 а"},
            {"Львів", "вулиця Григорія Смольського", "1 а"},
            {"Запоріжжя", "вулиця Стародніпровська", "12 а"},
            {"Миколаїв", "Прорізний провулок", "11 а"}
        };
        double[][] points = {{49.841952,24.0315921},{46.6401295,32.6143922},{48.4680221,35.0417711},
            {49.841952,24.0315921},{47.8507859,35.1182867},{46.9758615,31.9939666}};
        try (var sqlite = new JdbcSearchDb(new File(args[0],"search.db"));
             var nativeIndex = new TantivySearch(new File(args[0],"search.tantivy"))) {
            for (int i=0; i<cases.length; i++) {
                var entry=cases[i];
                String query=entry[0]+" "+entry[1]+" "+entry[2];
                var near=new GeoPoint(points[i][0],points[i][1]);
                var expected=AddressSearch.INSTANCE.search(sqlite,query,near,20,()->Unit.INSTANCE);
                try (var request=new TantivyRequest()) {
                    var actual=nativeIndex.search(query,near,request,()->Unit.INSTANCE);
                    if (!actual.equals(expected)) throw new AssertionError("Parity failed: "+query);
                    if (actual.isEmpty() || actual.get(0).getKind()!=ResultKind.ADDRESS ||
                        !actual.get(0).getTitle().equals(entry[1]+", "+entry[2]) ||
                        !actual.get(0).getSubtitle().equals(entry[0])) throw new AssertionError("Exact first hit missing: "+query);
                    System.out.println("exact_first_hit=true city="+entry[0]+" title="+actual.get(0).getTitle());
                }
            }
        }
    }
}
