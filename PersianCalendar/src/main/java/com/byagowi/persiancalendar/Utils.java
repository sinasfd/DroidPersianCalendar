package com.byagowi.persiancalendar;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceViewHolder;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.azizhuss.arabicreshaper.ArabicShaping;
import com.byagowi.persiancalendar.entity.City;
import com.byagowi.persiancalendar.entity.Event;
import com.byagowi.persiancalendar.enums.Season;
import com.byagowi.persiancalendar.locale.LocaleUtils;
import com.byagowi.persiancalendar.service.BroadcastReceivers;
import com.github.praytimes.CalculationMethod;
import com.github.praytimes.Clock;
import com.github.praytimes.Coordinate;
import com.github.praytimes.PrayTime;
import com.github.praytimes.PrayTimesCalculator;
import com.github.twaddington.TypefaceSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import calendar.AbstractDate;
import calendar.CivilDate;
import calendar.DateConverter;
import calendar.IslamicDate;
import calendar.LocaleData;
import calendar.PersianDate;

/**
 * Common utilities that needed for this calendar
 *
 * @author ebraminio
 */

public class Utils {
    private final String TAG = "Utils";
    private static Utils myInstance;
    private LocaleUtils localeUtils;
    private Context context;
    private Typeface typeface;
    private SharedPreferences prefs;

    private List<Event> events;
    private PrayTimesCalculator prayTimesCalculator;
    private Map<PrayTime, Clock> prayTimes;

    private String cachedCityKey = "";
    private City cachedCity;

    private Utils(Context context) {
        this.context = context;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        updateStoredPreference();
    }

    public static Utils getInstance(Context context) {
        if (myInstance == null) {
            myInstance = new Utils(context);
        }
        return myInstance;
    }

    /**
     * Text shaping is a essential thing on supporting Arabic script text on older Android versions.
     * It converts normal Arabic character to their presentation forms according to their position
     * on the text.
     * 
     * @param text Arabic string
     * @return Shaped text
     */
    public String shape(String text) {
        return (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN)
                ? ArabicShaping.shape(text)
                : text;
    }

    public String getString(String key) {
        return localeUtils == null
                ? ""
                : localeUtils.getString(key);
    }

    public String programVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Name not found on PersianCalendarUtils.programVersion");
            return "";
        }
    }

    private void initTypeface() {
        if (typeface == null) {
            typeface = Typeface.createFromAsset(context.getAssets(), Constants.FONT_PATH);
        }
    }

    public void prepareTextView(TextView textView) {
        initTypeface();
        textView.setTypeface(typeface);
    }

    public void prepareShapeTextView(TextView textView) {
        prepareTextView(textView);
        textView.setText(shape(textView.getText().toString()));
    }

    public void prepareShapePreference(PreferenceViewHolder holder) {
        // See android.support.v7.preference.Preference#onBindViewHolder
        TextView titleView = (TextView) holder.findViewById(android.R.id.title);
        if (titleView != null) {
            prepareShapeTextView(titleView);
        }
        TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
        if (summaryView != null) {
            prepareShapeTextView(summaryView);
        }
    }

    public void setActivityTitleAndSubtitle(Activity activity, String title, String subtitle) {
        if (title == null || subtitle == null) { return; }
        initTypeface();

        //noinspection ConstantConditions
        ActionBar supportActionBar = ((AppCompatActivity) activity).getSupportActionBar();

        if (supportActionBar == null) {
            return;
        }

        SpannableString titleSpan = new SpannableString(shape(title));
        titleSpan.setSpan(new TypefaceSpan(typeface), 0, titleSpan.length(), 0);
        titleSpan.setSpan(new RelativeSizeSpan(0.8f), 0, titleSpan.length(), 0);
        supportActionBar.setTitle(titleSpan);

        SpannableString subtitleSpan = new SpannableString(shape(subtitle));
        subtitleSpan.setSpan(new TypefaceSpan(typeface), 0, subtitleSpan.length(), 0);
        subtitleSpan.setSpan(new RelativeSizeSpan(0.8f), 0, subtitleSpan.length(), 0);
        supportActionBar.setSubtitle(subtitleSpan);
    }

    public CalculationMethod getCalculationMethod() {
        // It seems Iran is using Jafari method
        return CalculationMethod.valueOf(prefs.getString("PrayTimeMethod", "Jafari"));
    }

    public int getIslamicOffset() {
        return Integer.parseInt(prefs.getString("islamicOffset", "0"));
    }

    public Coordinate getCoordinate() {
        City city = getCityFromPreference();
        if (city != null) {
            return city.getCoordinate();
        }

        try {
            Coordinate coord = new Coordinate(
                    Double.parseDouble(prefs.getString("Latitude", "0")),
                    Double.parseDouble(prefs.getString("Longitude", "0")),
                    Double.parseDouble(prefs.getString("Altitude", "0"))
            );

            // If latitude or longitude is zero probably preference is not set yet
            if (coord.getLatitude() == 0 && coord.getLongitude() == 0) {
                return null;
            }

            return coord;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private char[] preferredDigits;
    private boolean clockIn24;
    public boolean iranTime;

    public void updateStoredPreference() {
        preferredDigits = prefs.getBoolean("PersianDigits", true)
                ? Constants.PERSIAN_DIGITS
                : Constants.ARABIC_DIGITS;

        clockIn24 = prefs.getBoolean("WidgetIn24", true);
        iranTime = prefs.getBoolean("IranTime", false);
    }

    public boolean isPersianDigitSelected(){
        return prefs.getBoolean("PersianDigits", true);
    }

    public boolean isWidgetClock() {
        return prefs.getBoolean("WidgetClock", true);
    }

    public boolean isNotifyDate() {
        return prefs.getBoolean("NotifyDate", true);
    }

    public int getAthanVolume() {
        return prefs.getInt("AthanVolume", 1);
    }

    public String getAppLanguage() {
        return prefs.getString("AppLanguage", "fa");
    }

    public String getSelectedWidgetTextColor() {
        return prefs.getString("SelectedWidgetTextColor", "#ffffffff");
    }

    public PersianDate getToday() {
        CivilDate civilDate = new CivilDate();
        return DateConverter.civilToPersian(civilDate);
    }

    public Calendar makeCalendarFromDate(Date date) {
        Calendar calendar = Calendar.getInstance();
        if (iranTime) {
            calendar.setTimeZone(TimeZone.getTimeZone("Asia/Tehran"));
        }
        calendar.setTime(date);
        return calendar;
    }

    public String clockToString(int hour, int minute) {
        return formatNumber(String.format(Locale.ENGLISH, "%d:%02d", hour, minute));
    }

    public String getNextOghatTime(Clock clock, boolean changeDate) {
        Coordinate coordinate = getCoordinate();

        if (coordinate != null) {
            if (prayTimesCalculator == null) {
                prayTimesCalculator = new PrayTimesCalculator(getCalculationMethod());
                changeDate = true;
            }

            if (changeDate) {
                prayTimes = prayTimesCalculator.calculate(new Date(), coordinate);
            }

            if (prayTimes.get(PrayTime.IMSAK).getInt() > clock.getInt()) {
                return context.getString(R.string.azan1) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.IMSAK));

            } else if (prayTimes.get(PrayTime.SUNRISE).getInt() > clock.getInt()) {
                return context.getString(R.string.aftab1) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.SUNRISE));

            } else if (prayTimes.get(PrayTime.DHUHR).getInt() > clock.getInt()) {
                return context.getString(R.string.azan2) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.DHUHR));

            } else if (prayTimes.get(PrayTime.ASR).getInt() > clock.getInt()) {
                return context.getString(R.string.azan3) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.ASR));

            } else if (prayTimes.get(PrayTime.SUNSET).getInt() > clock.getInt()) {
                return context.getString(R.string.aftab2) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.SUNSET));

            } else if (prayTimes.get(PrayTime.MAGHRIB).getInt() > clock.getInt()) {
                return context.getString(R.string.azan4) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.MAGHRIB));

            } else if (prayTimes.get(PrayTime.ISHA).getInt() > clock.getInt()) {
                return context.getString(R.string.azan5) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.ISHA));

            } else if (prayTimes.get(PrayTime.MIDNIGHT).getInt() > clock.getInt()) {
                return context.getString(R.string.aftab3) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.MIDNIGHT));

            } else {
                return context.getString(R.string.azan1) + ": " + getPersianFormattedClock(prayTimes.get(PrayTime.IMSAK)); //this is today & not tomorrow
            }

        } else return null;
    }

    public String getPersianFormattedClock(Clock clock) {
        String timeText = null;

        int hour = clock.getHour();
        if (!clockIn24) {
            if (hour >= 12) {
                timeText = Constants.PM_IN_PERSIAN;
                hour -= 12;
            } else {
                timeText = Constants.AM_IN_PERSIAN;
            }
        }

        String result = clockToString(hour, clock.getMinute());
        if (!clockIn24) {
            result = result + " " + timeText;
        }
        return result;
    }

    public String getPersianFormattedClock(Calendar calendar) {
        String timeText = null;

        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (!clockIn24) {
            if (calendar.get(Calendar.HOUR_OF_DAY) >= 12) {
                timeText = Constants.PM_IN_PERSIAN;
                hour -= 12;
            } else {
                timeText = Constants.AM_IN_PERSIAN;
            }
        }

        String result = clockToString(hour, calendar.get(Calendar.MINUTE));
        if (!clockIn24) {
            result = result + " " + timeText;
        }
        return result;
    }

    public String formatNumber(int number) {
        return formatNumber(Integer.toString(number));
    }

    public String formatNumber(String number) {
        if (preferredDigits == Constants.ARABIC_DIGITS)
            return number;

        StringBuilder sb = new StringBuilder();
        for (char i : number.toCharArray()) {
            if (Character.isDigit(i)) {
                sb.append(preferredDigits[Integer.parseInt(i + "")]);
            } else {
                sb.append(i);
            }
        }
        return sb.toString();
    }

    public String dateToString(AbstractDate date) {
        return formatNumber(date.getDayOfMonth()) + ' '
                + getMonthName(date) + ' '
                + formatNumber(date.getYear());
    }

    public String dayTitleSummary(PersianDate persianDate) {
        return getWeekDayName(persianDate) + Constants.PERSIAN_COMMA + " "
                + dateToString(persianDate);
    }

    public String getMonthName(AbstractDate date) {
        String monthName = "";
        // zero based
        int month = date.getMonth() - 1;

        if (date.getClass().equals(PersianDate.class)) {
            LocaleData.PersianMonthNames monthNameCode = LocaleData.PersianMonthNames.values()[month];
            monthName = getString(String.valueOf(monthNameCode));
        } else if (date.getClass().equals(CivilDate.class)) {
            LocaleData.CivilMonthNames monthNameCode = LocaleData.CivilMonthNames.values()[month];
            monthName = getString(String.valueOf(monthNameCode));
        } else if (date.getClass().equals(IslamicDate.class)) {
            LocaleData.IslamicMonthNames monthNameCode = LocaleData.IslamicMonthNames.values()[month];
            monthName = getString(String.valueOf(monthNameCode));
        }

        return monthName;
    }

    public List<String> getMonthNameList(AbstractDate date) {
        AbstractDate dateClone = date.clone();
        List<String> monthNameList = new ArrayList<>();
        dateClone.setDayOfMonth(1);
        for (int month = 1; month <= 12; ++month) {
            dateClone.setMonth(month);
            monthNameList.add(getMonthName(dateClone));
        }
        return monthNameList;
    }

    public String getWeekDayName(AbstractDate date) {
        CivilDate civilDate;
        if (date.getClass().equals(PersianDate.class)) {
            civilDate = DateConverter.persianToCivil((PersianDate) date);
        } else if (date.getClass().equals(IslamicDate.class)) {
            civilDate = DateConverter.islamicToCivil((IslamicDate) date);
        } else {
            civilDate = (CivilDate) date;
        }

        // zero based
        int dayOfWeek = civilDate.getDayOfWeek() - 1;
        LocaleData.WeekDayNames weekDayNameCode = LocaleData.WeekDayNames.values()[dayOfWeek];

        return getString(weekDayNameCode.toString());
    }

    public void quickToast(String message) {
        Toast.makeText(context, shape(message), Toast.LENGTH_SHORT).show();
    }

    public int getDayIconResource(int day) {
        try {
            return Constants.DAYS_ICONS[day];
        } catch (IndexOutOfBoundsException e) {
            Log.e("com.byagowi.calendar", "No such field is available");
            return 0;
        }
    }

    private String convertStreamToString(InputStream is) {
        // http://stackoverflow.com/a/5445161
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private String persianStringToArabic(String text) {
        return text
                .replaceAll("ی", "ي")
                .replaceAll("ک", "ك")
                .replaceAll("گ", "كی")
                .replaceAll("ژ", "زی")
                .replaceAll("چ", "جی")
                .replaceAll("پ", "بی");
    }

    private <T> Iterable<T> iteratorToIterable(final Iterator<T> iterator) {
        return new Iterable<T>() {
            @Override
            public Iterator<T> iterator() {
                return iterator;
            }
        };
    }


    public List<City> getAllCities(boolean needsSort) {
        ArrayList<City> result = new ArrayList<>();
        try {
            JSONObject countries = new JSONObject(convertStreamToString(
                    context.getResources().openRawResource(R.raw.cities)));

            for (String countryCode : iteratorToIterable(countries.keys())) {
                JSONObject country = countries.getJSONObject(countryCode);

                String countryEn = country.getString("en");
                String countryFa = country.getString("fa");

                JSONObject cities = country.getJSONObject("cities");

                for (String key : iteratorToIterable(cities.keys())) {
                    JSONObject city = cities.getJSONObject(key);

                    String en = city.getString("en");
                    String fa = city.getString("fa");

                    Coordinate coordinate = new Coordinate(
                            city.getDouble("latitude"),
                            city.getDouble("longitude"),
                            0 // city.getDouble("elevation")
                    );

                    result.add(new City(key, en, fa, countryCode, countryEn, countryFa, coordinate));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }

        if (!needsSort) {
            return result;
        }

        final String locale = prefs.getString("AppLanguage", "fa");

        City[] cities = result.toArray(new City[result.size()]);
        // Sort first by country code then city
        Arrays.sort(cities, new Comparator<City>() {
            @Override
            public int compare(City l, City r) {
                if (l.getKey().equals("CUSTOM")) {
                    return -1;
                }
                if (r.getKey().equals("CUSTOM")) {
                    return 1;
                }
                int compare = r.getCountryCode().compareTo(l.getCountryCode());
                if (compare != 0) return compare;
                if (locale.equals("en")) {
                    return l.getEn().compareTo(r.getEn());
                } else {
                    return persianStringToArabic(l.getFa())
                            .compareTo(persianStringToArabic(r.getFa()));
                }
            }
        });

        return Arrays.asList(cities);
    }

    public City getCityFromPreference() {
        String key = prefs.getString("Location", "");

        if (TextUtils.isEmpty(key) || key.equals("CUSTOM")) {
            return null;
        }

        if (key.equals(cachedCityKey)) {
            return cachedCity;
        }

        // cache last query even if no city avialable under the key, useful in case invalid
        // value is somehow inserted on the preference
        cachedCityKey = key;

        for (City city : getAllCities(false))
            if (city.getKey().equals(key)) {
                cachedCity = city;
                return city;
            }

        cachedCity = null;
        return null;
    }

    private ArrayList<Event> readEventsFromJSON(InputStream is) {
        ArrayList<Event> result = new ArrayList<>();
        try {
            JSONArray days = new JSONObject(convertStreamToString(is)).getJSONArray("events");

            int length = days.length();
            for (int i = 0; i < length; ++i) {
                JSONObject event = days.getJSONObject(i);

                int year = event.getInt("year");
                int month = event.getInt("month");
                int day = event.getInt("day");
                String title = event.getString("title");
                boolean holiday = event.getBoolean("holiday");

                result.add(new Event(new PersianDate(year, month, day), title, holiday));
            }

        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
        }
        return result;
    }

    public ArrayList<Event> getEvents(PersianDate day) {
        if (events == null) {
            events = readEventsFromJSON(context.getResources().openRawResource(R.raw.events));
        }

        ArrayList<Event> result = new ArrayList<>();
        for (Event event : events) {
            if (event.getDate().equals(day)) {
                result.add(event);
            }
        }
        return result;
    }

    public String getEventsTitle(PersianDate day, boolean holiday) {
        String titles = "";
        boolean first = true;
        ArrayList<Event> dayEvents = getEvents(day);

        for (int i = 0; i < dayEvents.size(); i++) {
            if (dayEvents.get(i).isHoliday() == holiday) {
                if (first) {
                    first = false;

                } else {
                    titles = titles + "\n";
                }
                titles = titles + dayEvents.get(i).getTitle();
            }
        }
        return titles;
    }

    public void loadApp() {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Calendar startTime = Calendar.getInstance();
        startTime.set(Calendar.HOUR_OF_DAY, 0);
        startTime.set(Calendar.MINUTE, 1);
        Intent intent = new Intent(context, BroadcastReceivers.class);
        intent.setAction(Constants.BROADCAST_RESTART_APP);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.set(AlarmManager.RTC, startTime.getTimeInMillis(), pendingIntent);
    }

    public boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public void loadAlarms() {
        Log.d(TAG, "reading and loading all alarms from prefs");
        String prefString = prefs.getString("AthanAlarm", "");
        CalculationMethod calculationMethod = getCalculationMethod();
        Coordinate coordinate = getCoordinate();

        if (calculationMethod != null && coordinate != null && !TextUtils.isEmpty(prefString)) {
            PrayTimesCalculator calculator = new PrayTimesCalculator(calculationMethod);
            Map<PrayTime, Clock> prayTimes = calculator.calculate(new Date(), coordinate);

            String[] alarmTimesNames = TextUtils.split(prefString, ",");
            for (int i = 0; i < alarmTimesNames.length; i++) {

                Clock alarmTime = prayTimes.get(PrayTime.valueOf(alarmTimesNames[i]));

                if (alarmTime != null) {
                    setAlarm(PrayTime.valueOf(alarmTimesNames[i]), alarmTime, i);
                }
            }
        }
    }

    public void setAlarm(PrayTime prayTime, Clock clock, int id) {
        Calendar triggerTime = Calendar.getInstance();
        triggerTime.set(Calendar.HOUR_OF_DAY, clock.getHour());
        triggerTime.set(Calendar.MINUTE, clock.getMinute());
        setAlarm(prayTime, triggerTime.getTimeInMillis(), id);
    }

    public void setAlarm(PrayTime prayTime, long timeInMillis, int id) {
        String valAthanGap = prefs.getString("AthanGap", "0");
        long athanGap;
        try {
            athanGap = (long)(Double.parseDouble(valAthanGap) * 60);
        } catch (NumberFormatException e) {
            athanGap = 0;
        }

        Calendar triggerTime = Calendar.getInstance();
        triggerTime.setTimeInMillis(timeInMillis - TimeUnit.SECONDS.toMillis(athanGap));
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        // don't set an alarm in the past
        if (!triggerTime.before(Calendar.getInstance())) {
            Log.d(TAG, "setting alarm for: " + triggerTime.getTime());

            Intent intent = new Intent(context, BroadcastReceivers.class);
            intent.setAction(Constants.BROADCAST_ALARM);
            intent.putExtra(Constants.KEY_EXTRA_PRAYER_KEY, prayTime.name());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, id, intent, 0);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime.getTimeInMillis(), pendingIntent);
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime.getTimeInMillis(), pendingIntent);
            }
        }
    }

    public Uri getAthanUri() {
        String defaultSoundUri = "android.resource://" + context.getPackageName() + "/" + R.raw.abdulbasit;
        return Uri.parse(defaultSoundUri);
    }

    public void changeAppLanguage(String localeCode) {
        Locale locale = TextUtils.isEmpty(localeCode) ? Locale.getDefault() : new Locale(localeCode);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.locale = locale;
        Resources resources = context.getResources();
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    private void changeCalendarLanguage(String localeCode) {
        if (localeUtils == null) {
            localeUtils = LocaleUtils.getInstance(context, localeCode);
        }

        localeUtils.changeLocale(localeCode);
    }

    public String loadLanguageFromSettings() {
        // set app language
        String locale = prefs.getString("AppLanguage", "fa");
        changeAppLanguage(locale.replaceAll("-(IR|AF)", ""));
        changeCalendarLanguage(locale);
        return locale;
    }

    public void copyToClipboard(View view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            return;
        }
        ClipboardManager clipboardManager =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        CharSequence date = ((TextView) view).getText();

        ClipData clip = ClipData.newPlainText("converted date", date);
        clipboardManager.setPrimaryClip(clip);

        quickToast("«" + date + "»\n" + context.getString(R.string.date_copied_clipboard));
    }

    public Season getSeason() {
        int month = getToday().getMonth();

        if (month < 4) {
            return Season.SPRING;

        } else if (month < 7) {
            return Season.SUMMER;

        } else if (month < 10) {
            return Season.FALL;

        } else {
            return Season.WINTER;
        }
    }
}
