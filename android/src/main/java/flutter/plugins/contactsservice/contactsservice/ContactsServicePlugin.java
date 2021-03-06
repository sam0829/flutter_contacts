package flutter.plugins.contactsservice.contactsservice;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

import static android.app.Activity.RESULT_CANCELED;
import static android.provider.ContactsContract.CommonDataKinds;
import static android.provider.ContactsContract.CommonDataKinds.Email;
import static android.provider.ContactsContract.CommonDataKinds.Organization;
import static android.provider.ContactsContract.CommonDataKinds.Phone;
import static android.provider.ContactsContract.CommonDataKinds.StructuredName;
import static android.provider.ContactsContract.CommonDataKinds.StructuredPostal;

@TargetApi(Build.VERSION_CODES.ECLAIR)
public class ContactsServicePlugin implements MethodCallHandler, FlutterPlugin, ActivityAware {

    private static final int FORM_OPERATION_CANCELED = 1;
    private static final int FORM_COULD_NOT_BE_OPEN = 2;

    private static final String LOG_TAG = "flutter_contacts";
    private ContentResolver contentResolver;
    private MethodChannel methodChannel;
    private BaseContactsServiceDelegate delegate;
    private EventChannel channel;
    private EventChannel updateChannel;
    private Activity mActivity;
    AddContactsTask mAddContactTask;
    UpdateContactsTask mUpdateContactsTask;

    private final ExecutorService executor =
            new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1000));

    private void initDelegateWithRegister(Registrar registrar) {
        this.delegate = new ContactServiceDelegateOld(registrar);
    }

    public static void registerWith(Registrar registrar) {
        ContactsServicePlugin instance = new ContactsServicePlugin();
        instance.initInstance(registrar.messenger(), registrar.context());
        instance.initDelegateWithRegister(registrar);
    }

    private void initInstance(BinaryMessenger messenger, Context context) {
        methodChannel = new MethodChannel(messenger, "github.com/clovisnicolas/flutter_contacts");
        methodChannel.setMethodCallHandler(this);
        channel = new EventChannel(messenger, "github.com/clovisnicolas/add_progress");
        channel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object args, final EventSink events) {
                        Log.e(LOG_TAG, "onListen*******");
                        ArrayList<HashMap> contactsMap = (ArrayList<HashMap>) args;
                        ArrayList<Contact> contacts = new ArrayList<>();
                        for (int i = 0; i < contactsMap.size(); i++) {
                            contacts.add(Contact.fromMap(contactsMap.get(i)));
                        }
                        mAddContactTask = new AddContactsTask(contacts, contentResolver, events, mActivity);
                        mAddContactTask.execute();
                    }

                    @Override
                    public void onCancel(Object args) {
                        Log.e(LOG_TAG, "Cancelled*******");
                        mAddContactTask.cancel(true);
                    }
                }
        );

        updateChannel = new EventChannel(messenger, "github.com/clovisnicolas/update_progress");
        updateChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object args, final EventSink events) {
                        ArrayList<HashMap> contactsMap = (ArrayList<HashMap>) args;
                        ArrayList<Contact> contacts = new ArrayList<>();
                        for (int i = 0; i < contactsMap.size(); i++) {
                            contacts.add(Contact.fromMap(contactsMap.get(i)));
                        }
                        mUpdateContactsTask = new UpdateContactsTask(contacts, contentResolver, events, mActivity);
                        mUpdateContactsTask.execute();
                    }

                    @Override
                    public void onCancel(Object args) {
                        mUpdateContactsTask.cancel(true);
                    }
                }
        );
        this.contentResolver = context.getContentResolver();
    }

    @Override
    public void onAttachedToEngine(FlutterPluginBinding binding) {
        initInstance(binding.getBinaryMessenger(), binding.getApplicationContext());
        this.delegate = new ContactServiceDelegate(binding.getApplicationContext());
    }

    @Override
    public void onDetachedFromEngine(FlutterPluginBinding binding) {
        methodChannel.setMethodCallHandler(null);
        methodChannel = null;
        contentResolver = null;
        this.delegate = null;
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        switch (call.method) {
            case "getContacts": {
                this.getContacts(call.method, (String) call.argument("query"), (boolean) call.argument("withThumbnails"), (boolean) call.argument("photoHighResolution"), (boolean) call.argument("orderByGivenName"), result);
                break;
            }
            case "getContactsForPhone": {
                this.getContactsForPhone(call.method, (String) call.argument("phone"), (boolean) call.argument("withThumbnails"), (boolean) call.argument("photoHighResolution"), (boolean) call.argument("orderByGivenName"), result);
                break;
            }
            case "getAvatar": {
                final Contact contact = Contact.fromMap((HashMap) call.argument("contact"));
                this.getAvatar(contact, (boolean) call.argument("photoHighResolution"), result);
                break;
            }
            case "addContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.addContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to add the contact", null);
                }
                break;
            }
            case "deleteContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.deleteContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to delete the contact, make sure it has a valid identifier", null);
                }
                break;
            }
            case "updateContact": {
                final Contact contact = Contact.fromMap((HashMap) call.arguments);
                if (this.updateContact(contact)) {
                    result.success(null);
                } else {
                    result.error(null, "Failed to update the contact, make sure it has a valid identifier", null);
                }
                break;
            }
            case "openExistingContact": {
                final Contact contact = Contact.fromMap((HashMap) call.argument("contact"));
                if (delegate != null) {
                    delegate.setResult(result);
                    delegate.openExistingContact(contact);
                } else {
                    result.success(FORM_COULD_NOT_BE_OPEN);
                }
                break;
            }
            case "openContactForm": {
                if (delegate != null) {
                    delegate.setResult(result);
                    delegate.openContactForm();
                } else {
                    result.success(FORM_COULD_NOT_BE_OPEN);
                }
                break;
            }
            case "openDeviceContactPicker": {
                openDeviceContactPicker(result);
                break;
            }
            default: {
                result.notImplemented();
                break;
            }
        }
    }

    private static final String[] PROJECTION =
            {
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.Profile.DISPLAY_NAME,
                    ContactsContract.Contacts.Data.MIMETYPE,
                    ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                    StructuredName.DISPLAY_NAME,
                    StructuredName.GIVEN_NAME,
                    StructuredName.MIDDLE_NAME,
                    StructuredName.FAMILY_NAME,
                    StructuredName.PREFIX,
                    StructuredName.SUFFIX,
                    CommonDataKinds.Note.NOTE,
                    Phone.NUMBER,
                    Phone.TYPE,
                    Phone.LABEL,
                    Email.DATA,
                    Email.ADDRESS,
                    Email.TYPE,
                    Email.LABEL,
                    Organization.COMPANY,
                    Organization.TITLE,
                    StructuredPostal.FORMATTED_ADDRESS,
                    StructuredPostal.TYPE,
                    StructuredPostal.LABEL,
                    StructuredPostal.STREET,
                    StructuredPostal.POBOX,
                    StructuredPostal.NEIGHBORHOOD,
                    StructuredPostal.CITY,
                    StructuredPostal.REGION,
                    StructuredPostal.POSTCODE,
                    StructuredPostal.COUNTRY,
            };


    @TargetApi(Build.VERSION_CODES.ECLAIR)
    private void getContacts(String callMethod, String query, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName, Result result) {
        new GetContactsTask(callMethod, result, withThumbnails, photoHighResolution, orderByGivenName).executeOnExecutor(executor, query, false);
    }

    private void getContactsForPhone(String callMethod, String phone, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName, Result result) {
        new GetContactsTask(callMethod, result, withThumbnails, photoHighResolution, orderByGivenName).executeOnExecutor(executor, phone, true);
    }

    @Override
    public void onAttachedToActivity(ActivityPluginBinding binding) {
        if (delegate instanceof ContactServiceDelegate) {
            mActivity = binding.getActivity();
            ((ContactServiceDelegate) delegate).bindToActivity(binding);
        }
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).unbindActivity();
        }
    }

    @Override
    public void onReattachedToActivityForConfigChanges(ActivityPluginBinding binding) {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).bindToActivity(binding);
        }
    }

    @Override
    public void onDetachedFromActivity() {
        if (delegate instanceof ContactServiceDelegate) {
            ((ContactServiceDelegate) delegate).unbindActivity();
        }
    }

    private class BaseContactsServiceDelegate implements PluginRegistry.ActivityResultListener {
        private static final int REQUEST_OPEN_CONTACT_FORM = 52941;
        private static final int REQUEST_OPEN_EXISTING_CONTACT = 52942;
        private static final int REQUEST_OPEN_CONTACT_PICKER = 52943;
        private Result result;

        void setResult(Result result) {
            this.result = result;
        }

        void finishWithResult(Object result) {
            if (this.result != null) {
                this.result.success(result);
                this.result = null;
            }
        }

        @Override
        public boolean onActivityResult(int requestCode, int resultCode, Intent intent) {
            if (requestCode == REQUEST_OPEN_EXISTING_CONTACT || requestCode == REQUEST_OPEN_CONTACT_FORM) {
                try {
                    Uri ur = intent.getData();
                    finishWithResult(getContactByIdentifier(ur.getLastPathSegment()));
                } catch (NullPointerException e) {
                    finishWithResult(FORM_OPERATION_CANCELED);
                }
                return true;
            }

            if (requestCode == REQUEST_OPEN_CONTACT_PICKER) {
                if (resultCode == RESULT_CANCELED) {
                    finishWithResult(FORM_OPERATION_CANCELED);
                    return true;
                }
                Uri contactUri = intent.getData();
                Cursor cursor = contentResolver.query(contactUri, null, null, null, null);
                if (cursor.moveToFirst()) {
                    String id = contactUri.getLastPathSegment();
                    getContacts("openDeviceContactPicker", id, false, false, false, this.result);
                } else {
                    Log.e(LOG_TAG, "onActivityResult - cursor.moveToFirst() returns false");
                    finishWithResult(FORM_OPERATION_CANCELED);
                }
                cursor.close();
                return true;
            }

            finishWithResult(FORM_COULD_NOT_BE_OPEN);
            return false;
        }

        void openExistingContact(Contact contact) {
            String identifier = contact.identifier;
            try {
                HashMap contactMapFromDevice = getContactByIdentifier(identifier);
                // Contact existence check
                if (contactMapFromDevice != null) {
                    Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, identifier);
                    Intent intent = new Intent(Intent.ACTION_EDIT);
                    intent.setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE);
                    intent.putExtra("finishActivityOnSaveCompleted", true);
                    startIntent(intent, REQUEST_OPEN_EXISTING_CONTACT);
                } else {
                    finishWithResult(FORM_COULD_NOT_BE_OPEN);
                }
            } catch (Exception e) {
                finishWithResult(FORM_COULD_NOT_BE_OPEN);
            }
        }

        void openContactForm() {
            try {
                Intent intent = new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI);
                intent.putExtra("finishActivityOnSaveCompleted", true);
                startIntent(intent, REQUEST_OPEN_CONTACT_FORM);
            } catch (Exception e) {
            }
        }

        void openContactPicker() {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
            startIntent(intent, REQUEST_OPEN_CONTACT_PICKER);
        }

        void startIntent(Intent intent, int request) {
        }

        HashMap getContactByIdentifier(String identifier) {
            ArrayList<Contact> matchingContacts;
            {
                Cursor cursor = contentResolver.query(
                        ContactsContract.Data.CONTENT_URI, PROJECTION,
                        ContactsContract.RawContacts.CONTACT_ID + " = ?",
                        new String[]{identifier},
                        null
                );
                try {
                    matchingContacts = getContactsFrom(cursor);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
            if (matchingContacts.size() > 0) {
                return matchingContacts.iterator().next().toMap();
            }
            return null;
        }
    }

    private void openDeviceContactPicker(Result result) {
        if (delegate != null) {
            delegate.setResult(result);
            delegate.openContactPicker();
        } else {
            result.success(FORM_COULD_NOT_BE_OPEN);
        }
    }

    private class ContactServiceDelegateOld extends BaseContactsServiceDelegate {
        private final PluginRegistry.Registrar registrar;

        ContactServiceDelegateOld(PluginRegistry.Registrar registrar) {
            this.registrar = registrar;
            registrar.addActivityResultListener(this);
        }

        @Override
        void startIntent(Intent intent, int request) {
            if (registrar.activity() != null) {
                registrar.activity().startActivityForResult(intent, request);
            } else {
                registrar.context().startActivity(intent);
            }
        }
    }

    private class ContactServiceDelegate extends BaseContactsServiceDelegate {
        private final Context context;
        private ActivityPluginBinding activityPluginBinding;

        ContactServiceDelegate(Context context) {
            this.context = context;
        }

        void bindToActivity(ActivityPluginBinding activityPluginBinding) {
            this.activityPluginBinding = activityPluginBinding;
            this.activityPluginBinding.addActivityResultListener(this);
        }

        void unbindActivity() {
            this.activityPluginBinding.removeActivityResultListener(this);
            this.activityPluginBinding = null;
        }

        @Override
        void startIntent(Intent intent, int request) {
            if (this.activityPluginBinding != null) {
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    activityPluginBinding.getActivity().startActivityForResult(intent, request);
                } else {
                    finishWithResult(FORM_COULD_NOT_BE_OPEN);
                }
            } else {
                context.startActivity(intent);
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    private class GetContactsTask extends AsyncTask<Object, Void, ArrayList<HashMap>> {

        private String callMethod;
        private Result getContactResult;
        private boolean withThumbnails;
        private boolean photoHighResolution;
        private boolean orderByGivenName;

        public GetContactsTask(String callMethod, Result result, boolean withThumbnails, boolean photoHighResolution, boolean orderByGivenName) {
            this.callMethod = callMethod;
            this.getContactResult = result;
            this.withThumbnails = withThumbnails;
            this.photoHighResolution = photoHighResolution;
            this.orderByGivenName = orderByGivenName;
        }

        @TargetApi(Build.VERSION_CODES.ECLAIR)
        protected ArrayList<HashMap> doInBackground(Object... params) {
            ArrayList<Contact> contacts;
            switch (callMethod) {
                case "openDeviceContactPicker":
                    contacts = getContactsFrom(getCursor(null, (String) params[0]));
                    break;
                case "getContacts":
                    contacts = getContactsFrom(getCursor((String) params[0], null));
                    break;
                case "getContactsForPhone":
                    contacts = getContactsFrom(getCursorForPhone(((String) params[0])));
                    break;
                default:
                    return null;
            }

            if (withThumbnails) {
                for (Contact c : contacts) {
                    final byte[] avatar = loadContactPhotoHighRes(
                            c.identifier, photoHighResolution, contentResolver);
                    if (avatar != null) {
                        c.avatar = avatar;
                    } else {
                        // To stay backwards-compatible, return an empty byte array rather than `null`.
                        c.avatar = new byte[0];
                    }
//          if ((Boolean) params[3])
//              loadContactPhotoHighRes(c.identifier, (Boolean) params[3]);
//          else
//              setAvatarDataForContactIfAvailable(c);
                }
            }

            if (orderByGivenName) {
                Comparator<Contact> compareByGivenName = new Comparator<Contact>() {
                    @Override
                    public int compare(Contact contactA, Contact contactB) {
                        return contactA.compareTo(contactB);
                    }
                };
                Collections.sort(contacts, compareByGivenName);
            }

            //Transform the list of contacts to a list of Map
            ArrayList<HashMap> contactMaps = new ArrayList<>();
            for (Contact c : contacts) {
                contactMaps.add(c.toMap());
            }

            return contactMaps;
        }

        protected void onPostExecute(ArrayList<HashMap> result) {
            if (result == null) {
                getContactResult.notImplemented();
            } else {
                getContactResult.success(result);
            }
        }
    }


    private Cursor getCursor(String query, String rawContactId) {
        String selection = "(" + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.Data.MIMETYPE + "=? OR "
                + ContactsContract.Data.MIMETYPE + "=? OR " + ContactsContract.RawContacts.ACCOUNT_TYPE + "=?" + ")";
        ArrayList<String> selectionArgs = new ArrayList<>(Arrays.asList(CommonDataKinds.Note.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE,
                Phone.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
                StructuredPostal.CONTENT_ITEM_TYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.RawContacts.ACCOUNT_TYPE));
        if (query != null) {
            selectionArgs = new ArrayList<>();
            selectionArgs.add(query + "%");
            selection = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " LIKE ?";
        }
        if (rawContactId != null) {
            selectionArgs.add(rawContactId);
            selection += " AND " + ContactsContract.Data.CONTACT_ID + " =?";
        }
        return contentResolver.query(ContactsContract.Data.CONTENT_URI, PROJECTION, selection, selectionArgs.toArray(new String[selectionArgs.size()]), null);
    }

    private Cursor getCursorForPhone(String phone) {
        if (phone.isEmpty())
            return null;

        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone));
        String[] projection = new String[]{BaseColumns._ID};

        ArrayList<String> contactIds = new ArrayList<>();
        Cursor phoneCursor = contentResolver.query(uri, projection, null, null, null);
        while (phoneCursor != null && phoneCursor.moveToNext()) {
            contactIds.add(phoneCursor.getString(phoneCursor.getColumnIndex(BaseColumns._ID)));
        }
        if (phoneCursor != null)
            phoneCursor.close();

        if (!contactIds.isEmpty()) {
            String contactIdsListString = contactIds.toString().replace("[", "(").replace("]", ")");
            String contactSelection = ContactsContract.Data.CONTACT_ID + " IN " + contactIdsListString;
            return contentResolver.query(ContactsContract.Data.CONTENT_URI, PROJECTION, contactSelection, null, null);
        }

        return null;
    }

    /**
     * Builds the list of contacts from the cursor
     *
     * @param cursor
     * @return the list of contacts
     */
    private ArrayList<Contact> getContactsFrom(Cursor cursor) {
        HashMap<String, Contact> map = new LinkedHashMap<>();

        while (cursor != null && cursor.moveToNext()) {
            int columnIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID);
            String contactId = cursor.getString(columnIndex);

            if (!map.containsKey(contactId)) {
                map.put(contactId, new Contact(contactId));
            }
            Contact contact = map.get(contactId);

            String mimeType = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.MIMETYPE));
            contact.displayName = cursor.getString(cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
            contact.androidAccountType = cursor.getString(cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE));
            contact.androidAccountName = cursor.getString(cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME));

            //NAMES
            if (mimeType.equals(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)) {
                contact.givenName = cursor.getString(cursor.getColumnIndex(StructuredName.GIVEN_NAME));
                contact.middleName = cursor.getString(cursor.getColumnIndex(StructuredName.MIDDLE_NAME));
                contact.familyName = cursor.getString(cursor.getColumnIndex(StructuredName.FAMILY_NAME));
                contact.prefix = cursor.getString(cursor.getColumnIndex(StructuredName.PREFIX));
                contact.suffix = cursor.getString(cursor.getColumnIndex(StructuredName.SUFFIX));
            }
            // NOTE
            else if (mimeType.equals(CommonDataKinds.Note.CONTENT_ITEM_TYPE)) {
                contact.note = cursor.getString(cursor.getColumnIndex(CommonDataKinds.Note.NOTE));
            }
            //PHONES
            else if (mimeType.equals(CommonDataKinds.Phone.CONTENT_ITEM_TYPE)) {
                String phoneNumber = cursor.getString(cursor.getColumnIndex(Phone.NUMBER));
                if (!TextUtils.isEmpty(phoneNumber)) {
                    int type = cursor.getInt(cursor.getColumnIndex(Phone.TYPE));
                    String label = Item.getPhoneLabel(type, cursor);
                    contact.phones.add(new Item(label, phoneNumber));
                }
            }
            //MAILS
            else if (mimeType.equals(CommonDataKinds.Email.CONTENT_ITEM_TYPE)) {
                String email = cursor.getString(cursor.getColumnIndex(Email.ADDRESS));
                int type = cursor.getInt(cursor.getColumnIndex(Email.TYPE));
                if (!TextUtils.isEmpty(email)) {
                    contact.emails.add(new Item(Item.getEmailLabel(type, cursor), email));
                }
            }
            //ORG
            else if (mimeType.equals(CommonDataKinds.Organization.CONTENT_ITEM_TYPE)) {
                contact.company = cursor.getString(cursor.getColumnIndex(Organization.COMPANY));
                contact.jobTitle = cursor.getString(cursor.getColumnIndex(Organization.TITLE));
            }
            //ADDRESSES
            else if (mimeType.equals(CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)) {
                contact.postalAddresses.add(new PostalAddress(cursor));
            }
            // BIRTHDAY
            else if (mimeType.equals(CommonDataKinds.Event.CONTENT_ITEM_TYPE)) {
                int eventType = cursor.getInt(cursor.getColumnIndex(CommonDataKinds.Event.TYPE));
                if (eventType == CommonDataKinds.Event.TYPE_BIRTHDAY) {
                    contact.birthday = cursor.getString(cursor.getColumnIndex(CommonDataKinds.Event.START_DATE));
                }
            }
        }

        if (cursor != null)
            cursor.close();

        return new ArrayList<>(map.values());
    }

    private void setAvatarDataForContactIfAvailable(Contact contact) {
        Uri contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, Integer.parseInt(contact.identifier));
        Uri photoUri = Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY);
        Cursor avatarCursor = contentResolver.query(photoUri,
                new String[]{ContactsContract.Contacts.Photo.PHOTO}, null, null, null);
        if (avatarCursor != null && avatarCursor.moveToFirst()) {
            byte[] avatar = avatarCursor.getBlob(0);
            contact.avatar = avatar;
        }
        if (avatarCursor != null) {
            avatarCursor.close();
        }
    }

    private void getAvatar(final Contact contact, final boolean highRes,
                           final Result result) {
        new GetAvatarsTask(contact, highRes, contentResolver, result).executeOnExecutor(this.executor);
    }

    private static class GetAvatarsTask extends AsyncTask<Void, Void, byte[]> {
        final Contact contact;
        final boolean highRes;
        final ContentResolver contentResolver;
        final Result result;

        GetAvatarsTask(final Contact contact, final boolean highRes,
                       final ContentResolver contentResolver, final Result result) {
            this.contact = contact;
            this.highRes = highRes;
            this.contentResolver = contentResolver;
            this.result = result;
        }

        @Override
        protected byte[] doInBackground(final Void... params) {
            // Load avatar for each contact identifier.
            return loadContactPhotoHighRes(contact.identifier, highRes, contentResolver);
        }

        @Override
        protected void onPostExecute(final byte[] avatar) {
            result.success(avatar);
        }
    }

    private static byte[] loadContactPhotoHighRes(final String identifier,
                                                  final boolean photoHighResolution, final ContentResolver contentResolver) {
        try {
            final Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, Long.parseLong(identifier));
            final InputStream input = ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, uri, photoHighResolution);

            if (input == null) return null;

            final Bitmap bitmap = BitmapFactory.decodeStream(input);
            input.close();

            final ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            final byte[] bytes = stream.toByteArray();
            stream.close();
            return bytes;
        } catch (final IOException ex) {
            Log.e(LOG_TAG, ex.getMessage());
            return null;
        }
    }

    private boolean addContact(Contact contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();

        addSingleContact(contact, ops, 0);

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addSingleContact(Contact contact, ArrayList<ContentProviderOperation> ops, int index) {
        int id = ops.size();

        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());


        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(StructuredName.FAMILY_NAME, contact.familyName)
                .withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                //.withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                //.withValue(StructuredName.FAMILY_NAME, contact.familyName)
                //.withValue(StructuredName.PREFIX, contact.prefix)
                //.withValue(StructuredName.SUFFIX, contact.suffix)
                .build());


        if (contact.note != null && !contact.note.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Note.NOTE, contact.note).build());
        }

        if ((contact.company != null && !contact.company.isEmpty()) || (contact.jobTitle != null && !contact.jobTitle.isEmpty())) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                    .withValue(ContactsContract.Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.COMPANY, contact.company)
                    .withValue(Organization.TITLE, contact.jobTitle).build());
        }

        //Photo
        if (contact.avatar != null && contact.avatar.length > 0) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                    .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                    .withValue(CommonDataKinds.Photo.PHOTO, contact.avatar)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Photo.CONTENT_ITEM_TYPE).build());
        }

        //op.withYieldAllowed(true);

        //Phones
        if (contact.phones != null && contact.phones.size() > 0) {
            for (Item phone : contact.phones) {
                ContentProviderOperation.Builder op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                        .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, phone.value)
                        .withValue(
                                ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        );

              /*if (Item.stringToPhoneType(phone.label) == Phone.TYPE_CUSTOM) {
                  op.withValue(Phone.TYPE, CommonDataKinds.BaseTypes.TYPE_CUSTOM);
                  op.withValue(Phone.LABEL, phone.label);
              } else
                  op.withValue(Phone.TYPE, Item.stringToPhoneType(phone.label));*/

                ops.add(op.build());
            }
        }

        //Emails
        if (contact.emails != null && contact.emails.size() > 0) {
            for (Item email : contact.emails) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                        .withValue(ContactsContract.Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, email.value)
                        .withValue(Email.TYPE, Item.stringToEmailType(email.label)).build());
            }
        }

        //Postal addresses
        if (contact.postalAddresses != null && contact.postalAddresses.size() > 0) {
            for (PostalAddress address : contact.postalAddresses) {
                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                        .withValue(ContactsContract.Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE)
                        .withValue(StructuredPostal.TYPE, PostalAddress.stringToPostalAddressType(address.label))
                        .withValue(StructuredPostal.LABEL, address.label)
                        .withValue(StructuredPostal.STREET, address.street)
                        .withValue(StructuredPostal.CITY, address.city)
                        .withValue(StructuredPostal.REGION, address.region)
                        .withValue(StructuredPostal.POSTCODE, address.postcode)
                        .withValue(StructuredPostal.COUNTRY, address.country).build());
            }
        }

        // Birthday
        if (contact.birthday != null && !contact.birthday.isEmpty()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, id)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                    .withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
                    .withValue(CommonDataKinds.Event.START_DATE, contact.birthday).build());
        }
    }

    private static void updateSingleContact(Contact contact, ArrayList<ContentProviderOperation> ops, ContentResolver contentResolver) {
        ContentProviderOperation.Builder op;

        String rawId = getRawContactId(contact.identifier, contentResolver);
        if (rawId == null) return;

        // Update data (name)
        op = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(StructuredName.FAMILY_NAME, contact.familyName)
                .withValue(StructuredName.MIDDLE_NAME, contact.middleName);
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        if (contact.emails != null && contact.emails.size() > 0) {
            for (Item email : contact.emails) {
                op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, email.value)
                        .withValue(Email.TYPE, Item.stringToEmailType(email.label));

                ops.add(op.build());
            }
        }

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{rawId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        if (contact.company != null && !contact.company.isEmpty()) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(Organization.COMPANY, contact.company)
                    .withValue(Organization.TITLE, contact.jobTitle);

            ops.add(op.build());
        }
    }

    private boolean deleteContact(Contact contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(ContactsContract.RawContacts.CONTACT_ID + "=?", new String[]{String.valueOf(contact.identifier)})
                .build());
        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;

        }
    }

    private boolean updateContact(Contact contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ContentProviderOperation.Builder op;

        // Drop all details about contact except name
        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        //Photo
        op = ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE});
        ops.add(op.build());

        // Update data (name)
        op = ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data.CONTACT_ID + "=? AND " + ContactsContract.Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(contact.identifier), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                .withValue(StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(StructuredName.MIDDLE_NAME, contact.middleName)
                .withValue(StructuredName.FAMILY_NAME, contact.familyName)
                .withValue(StructuredName.PREFIX, contact.prefix)
                .withValue(StructuredName.SUFFIX, contact.suffix);
        ops.add(op.build());

        // Insert data back into contact
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(Organization.TYPE, Organization.TYPE_WORK)
                .withValue(Organization.COMPANY, contact.company)
                .withValue(Organization.TITLE, contact.jobTitle);
        ops.add(op.build());

        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(CommonDataKinds.Note.NOTE, contact.note);
        ops.add(op.build());

        //Photo
        op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                .withValue(ContactsContract.Data.IS_SUPER_PRIMARY, 1)
                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, contact.avatar)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE);
        ops.add(op.build());


        for (Item phone : contact.phones) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(Phone.NUMBER, phone.value);

            if (Item.stringToPhoneType(phone.label) == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM) {
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM);
                op.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, phone.label);
            } else
                op.withValue(ContactsContract.CommonDataKinds.Phone.TYPE, Item.stringToPhoneType(phone.label));

            ops.add(op.build());
        }

        for (Item email : contact.emails) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(CommonDataKinds.Email.ADDRESS, email.value)
                    .withValue(CommonDataKinds.Email.TYPE, Item.stringToEmailType(email.label));
            ops.add(op.build());
        }

        for (PostalAddress address : contact.postalAddresses) {
            op = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, contact.identifier)
                    .withValue(CommonDataKinds.StructuredPostal.TYPE, PostalAddress.stringToPostalAddressType(address.label))
                    .withValue(CommonDataKinds.StructuredPostal.STREET, address.street)
                    .withValue(CommonDataKinds.StructuredPostal.CITY, address.city)
                    .withValue(CommonDataKinds.StructuredPostal.REGION, address.region)
                    .withValue(CommonDataKinds.StructuredPostal.POSTCODE, address.postcode)
                    .withValue(CommonDataKinds.StructuredPostal.COUNTRY, address.country);
            ops.add(op.build());
        }

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            // Log exception
            Log.e("TAG", "Exception encountered while inserting contact: ");
            e.printStackTrace();
            return false;
        }
    }

    private static class AddContactsTask extends AsyncTask<Void, Void, Void> {
        ArrayList<Contact> contacts;
        ContentResolver contentResolver;
        EventSink events;
        Activity activity;

        public AddContactsTask(ArrayList<Contact> contacts,
                               ContentResolver contentResolver,
                               EventSink events, Activity activity) {
            this.contacts = contacts;
            this.contentResolver = contentResolver;
            this.events = events;
            this.activity = activity;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            int batchSize = 30;
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();

            for (int i = 0; i < contacts.size(); i++) {
                if (isCancelled()) {
                    Log.e(LOG_TAG, "Task is cancelled, aborting!!");
                    break;
                }
                Contact contact = contacts.get(i);
                addSingleContact(contact, ops, i);

                if (ops.size() >= batchSize || i == contacts.size() - 1) {
                    try {
                        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
                        ops.clear();
                        final int finalI = i;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                events.success(finalI + 1);
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }
    }

    private static class UpdateContactsTask extends AsyncTask<Void, Void, Void> {
        ArrayList<Contact> contacts;
        ContentResolver contentResolver;
        EventSink events;
        Activity activity;

        public UpdateContactsTask(ArrayList<Contact> contacts,
                                  ContentResolver contentResolver,
                                  EventSink events, Activity activity) {
            this.contacts = contacts;
            this.contentResolver = contentResolver;
            this.events = events;
            this.activity = activity;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            int batchSize = 30;
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();

            for (int i = 0; i < contacts.size(); i++) {
                if (isCancelled()) {
                    Log.e(LOG_TAG, "Task is cancelled, aborting!!");
                    break;
                }
                Contact contact = contacts.get(i);
                updateSingleContact(contact, ops, contentResolver);

                if (ops.size() >= batchSize || i == contacts.size() - 1) {
                    try {
                        contentResolver.applyBatch(ContactsContract.AUTHORITY, ops);
                        ops.clear();
                        final int finalI = i;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                events.success(finalI + 1);
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            return null;
        }
    }

    public static String getRawContactId(String contactId, ContentResolver contentResolver) {
        String res = null;
        Uri uri = ContactsContract.RawContacts.CONTENT_URI;
        String[] projection = new String[]{ContactsContract.RawContacts._ID};
        String selection = ContactsContract.RawContacts.CONTACT_ID + " = ?";
        String[] selectionArgs = new String[]{contactId};
        Cursor c = contentResolver.query(uri, projection, selection, selectionArgs, null);

        if (c != null && c.moveToFirst()) {
            res = c.getString(c.getColumnIndex(ContactsContract.RawContacts._ID));
            c.close();
        }

        return res;
    }
}
