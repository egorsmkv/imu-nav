//! JNI surface: the same eight native methods the `AndroidX` 1.0.1 C++ library registers.
//!
//! Reading Skia's private `SkPathRef` is inherently unsafe: its layout is not an API. Like the
//! C++, the layout is chosen by Android version; unlike it, every array becomes a slice with
//! the length Skia records, and the iterator never indexes outside those slices.

use crate::conic::conic_to_quadratics;
use crate::iterator::{ConicEvaluation, PathIterator, VerbDirection};
use crate::{Point, Verb};
use jni::objects::{JClass, JFieldID, JFloatArray, JObject};
use jni::sys::{JNI_ERR, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6, jboolean, jfloat, jint, jlong};
use jni::{JNIEnv, JavaVM, NativeMethod};
use std::ffi::{c_char, c_int, c_void};
use std::sync::OnceLock;

const ITERATOR_CLASS: &str = "androidx/graphics/path/PathIteratorPreApi34Impl";
const CONVERTER_CLASS: &str = "androidx/graphics/path/ConicConverter";

/// `android.graphics.Path.mNativePath`, resolved once in `JNI_OnLoad`.
static NATIVE_PATH: OnceLock<JFieldID> = OnceLock::new();

unsafe extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
}

/// `android_get_device_api_level` only exists in libc from API 29, so read the property as the
/// NDK's inline fallback does.
fn device_api_level() -> u32 {
    static LEVEL: OnceLock<u32> = OnceLock::new();
    *LEVEL.get_or_init(|| {
        let mut value = [0u8; 92]; // PROP_VALUE_MAX
        // SAFETY: the name is NUL-terminated and the buffer has PROP_VALUE_MAX bytes.
        let length = unsafe {
            __system_property_get(
                c"ro.build.version.sdk".as_ptr(),
                value.as_mut_ptr().cast::<c_char>(),
            )
        };
        let length = usize::try_from(length).unwrap_or(0).min(value.len());
        std::str::from_utf8(&value[..length])
            .ok()
            .and_then(|text| text.trim().parse().ok())
            .unwrap_or(0)
    })
}

/// Fields of `SkPathRef` used by `AndroidX`, per platform layout (see `Path.h` upstream).
#[repr(C)]
struct PathRef24 {
    vtable: isize,
    ref_count: i32,
    bounds: [f32; 4],
    points: *const Point,
    verbs: *const u8,
    verb_count: i32,
    point_count: i32,
    free_space: usize,
    conic_weights: *const f32,
    conic_weights_reserve: i32,
    conic_weights_count: i32,
}

#[repr(C)]
struct PathRef26 {
    ref_count: i32,
    bounds: [f32; 4],
    points: *const Point,
    verbs: *const u8,
    verb_count: i32,
    point_count: i32,
    free_space: usize,
    conic_weights: *const f32,
    conic_weights_reserve: i32,
    conic_weights_count: i32,
}

#[repr(C)]
struct PathRef30 {
    ref_count: i32,
    bounds: [f32; 4],
    points: *const Point,
    point_reserve: i32,
    point_count: i32,
    verbs: *const u8,
    verb_reserve: i32,
    verb_count: i32,
    conic_weights: *const f32,
    conic_weights_reserve: i32,
    conic_weights_count: i32,
}

/// Raw arrays of one path: verbs start at `verbs` (forward) or end there (backward).
struct RawPath {
    points: *const Point,
    point_count: i32,
    verbs: *const u8,
    verb_count: i32,
    weights: *const f32,
    weight_count: i32,
    direction: VerbDirection,
}

/// # Safety
/// `path_ref` must point to a live `SkPathRef` of the layout for `api`.
unsafe fn read_path_ref(path_ref: *const c_void, api: u32) -> RawPath {
    // SAFETY: the caller guarantees the layout; only plain fields are read.
    unsafe {
        if api >= 30 {
            let r = &*path_ref.cast::<PathRef30>();
            RawPath {
                points: r.points,
                point_count: r.point_count,
                verbs: r.verbs,
                verb_count: r.verb_count,
                weights: r.conic_weights,
                weight_count: r.conic_weights_count,
                direction: VerbDirection::Forward,
            }
        } else if api >= 26 {
            let r = &*path_ref.cast::<PathRef26>();
            RawPath {
                points: r.points,
                point_count: r.point_count,
                verbs: r.verbs,
                verb_count: r.verb_count,
                weights: r.conic_weights,
                weight_count: r.conic_weights_count,
                direction: VerbDirection::Backward,
            }
        } else {
            let r = &*path_ref.cast::<PathRef24>();
            RawPath {
                points: r.points,
                point_count: r.point_count,
                verbs: r.verbs,
                verb_count: r.verb_count,
                weights: r.conic_weights,
                weight_count: r.conic_weights_count,
                direction: VerbDirection::Backward,
            }
        }
    }
}

/// # Safety
/// `pointer` must be null or valid for `count` elements while the path is not modified, which
/// `AndroidX` guarantees for the iterator's lifetime (as with the C++ library).
unsafe fn slice<T>(pointer: *const T, count: i32) -> &'static [T] {
    match usize::try_from(count) {
        // SAFETY: see the function contract.
        Ok(length) if length > 0 && !pointer.is_null() => unsafe {
            std::slice::from_raw_parts(pointer, length)
        },
        _ => &[],
    }
}

type Iterator = PathIterator<'static>;

extern "system" fn create(
    mut env: JNIEnv,
    _: JObject,
    path: JObject,
    evaluation: jint,
    tolerance: jfloat,
) -> jlong {
    let Some(field) = NATIVE_PATH.get() else {
        return 0;
    };
    let Ok(native) = env
        .get_field_unchecked(
            &path,
            *field,
            jni::signature::ReturnType::Primitive(jni::signature::Primitive::Long),
        )
        .and_then(jni::objects::JValueGen::j)
    else {
        return 0;
    };
    let empty = || {
        Box::into_raw(Box::new(PathIterator::new(
            &[],
            &[],
            &[],
            VerbDirection::Forward,
            ConicEvaluation::from_raw(evaluation),
            tolerance,
        ))) as jlong
    };
    if native == 0 {
        return empty();
    }
    // SAFETY: mNativePath is an SkPath*, whose first field is its SkPathRef*.
    let path_ref = unsafe { *(native as *const *const c_void) };
    if path_ref.is_null() {
        return empty();
    }
    // SAFETY: the layout is selected by the platform version, as in `AndroidX`.
    let raw = unsafe { read_path_ref(path_ref, device_api_level()) };
    // SAFETY: Skia keeps these arrays alive and unchanged while the path is being iterated.
    let (points, verbs, weights) = unsafe {
        let verbs = match raw.direction {
            VerbDirection::Forward => slice(raw.verbs, raw.verb_count),
            // Backward layouts store the verbs just before `verbs`.
            VerbDirection::Backward => match usize::try_from(raw.verb_count) {
                Ok(count) if count > 0 && !raw.verbs.is_null() => {
                    slice(raw.verbs.sub(count), raw.verb_count)
                }
                _ => &[],
            },
        };
        (
            slice(raw.points, raw.point_count),
            verbs,
            slice(raw.weights, raw.weight_count),
        )
    };
    Box::into_raw(Box::new(PathIterator::new(
        points,
        verbs,
        weights,
        raw.direction,
        ConicEvaluation::from_raw(evaluation),
        tolerance,
    ))) as jlong
}

/// # Safety
/// `handle` must come from `create` and not be destroyed yet.
unsafe fn iterator<'a>(handle: jlong) -> Option<&'a mut Iterator> {
    // SAFETY: `AndroidX` passes back the handle `create` returned, from one thread at a time.
    unsafe { (handle as *mut Iterator).as_mut() }
}

extern "system" fn destroy(_: JNIEnv, _: JObject, handle: jlong) {
    if handle != 0 {
        // SAFETY: created by Box::into_raw in `create`; `AndroidX` destroys it exactly once.
        drop(unsafe { Box::from_raw(handle as *mut Iterator) });
    }
}

extern "system" fn has_next(_: JNIEnv, _: JObject, handle: jlong) -> jboolean {
    // SAFETY: see `iterator`.
    if unsafe { iterator(handle) }.is_some_and(|it| it.has_next()) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

extern "system" fn next(
    env: JNIEnv,
    _: JObject,
    handle: jlong,
    points: JFloatArray,
    offset: jint,
) -> jint {
    // SAFETY: see `iterator`.
    let Some(it) = (unsafe { iterator(handle) }) else {
        return Verb::Done as jint;
    };
    let mut out = [Point::default(); 4];
    let verb = it.next(&mut out);
    if verb != Verb::Done && verb != Verb::Close {
        let floats: [f32; 8] = [
            out[0].x, out[0].y, out[1].x, out[1].y, out[2].x, out[2].y, out[3].x, out[3].y,
        ];
        // A too-small array raises ArrayIndexOutOfBoundsException in Java, as with the C++.
        let _ = env.set_float_array_region(&points, offset, &floats);
    }
    verb as jint
}

extern "system" fn peek(_: JNIEnv, _: JObject, handle: jlong) -> jint {
    // SAFETY: see `iterator`.
    unsafe { iterator(handle) }.map_or(Verb::Done, |it| it.peek()) as jint
}

extern "system" fn raw_size(_: JNIEnv, _: JObject, handle: jlong) -> jint {
    // SAFETY: see `iterator`.
    unsafe { iterator(handle) }.map_or(0, |it| jint::try_from(it.raw_count()).unwrap_or(jint::MAX))
}

extern "system" fn size(_: JNIEnv, _: JObject, handle: jlong) -> jint {
    // SAFETY: see `iterator`.
    unsafe { iterator(handle) }.map_or(0, |it| jint::try_from(it.count()).unwrap_or(jint::MAX))
}

extern "system" fn conic_to_quadratics_jni(
    env: JNIEnv,
    _: JObject,
    conic: JFloatArray,
    offset: jint,
    quadratics: JFloatArray,
    weight: jfloat,
    tolerance: jfloat,
) -> jint {
    let mut input = [0f32; 6];
    if env
        .get_float_array_region(&conic, offset, &mut input)
        .is_err()
    {
        return 0;
    }
    let Ok(length) = env.get_array_length(&quadratics) else {
        return 0;
    };
    let mut output = vec![0f32; usize::try_from(length).unwrap_or(0)];
    let points = [
        Point {
            x: input[0],
            y: input[1],
        },
        Point {
            x: input[2],
            y: input[3],
        },
        Point {
            x: input[4],
            y: input[5],
        },
    ];
    let count = conic_to_quadratics(points, &mut output, weight, tolerance);
    if 2 * (2 * count + 1) <= output.len() {
        let _ = env.set_float_array_region(&quadratics, 0, &output[..2 * (2 * count + 1)]);
    }
    jint::try_from(count).unwrap_or(jint::MAX)
}

fn method(name: &str, signature: &str, function: *mut c_void) -> NativeMethod {
    NativeMethod {
        name: name.into(),
        sig: signature.into(),
        fn_ptr: function,
    }
}

fn register(env: &mut JNIEnv) -> jni::errors::Result<()> {
    let path: JClass = env.find_class("android/graphics/Path")?;
    let field = env.get_field_id(&path, "mNativePath", "J")?;
    let _ = NATIVE_PATH.set(field);
    let iterator_class = env.find_class(ITERATOR_CLASS)?;
    env.register_native_methods(
        &iterator_class,
        &[
            method(
                "createInternalPathIterator",
                "(Landroid/graphics/Path;IF)J",
                create as *mut c_void,
            ),
            method(
                "destroyInternalPathIterator",
                "(J)V",
                destroy as *mut c_void,
            ),
            method(
                "internalPathIteratorHasNext",
                "(J)Z",
                has_next as *mut c_void,
            ),
            method("internalPathIteratorNext", "(J[FI)I", next as *mut c_void),
            method("internalPathIteratorPeek", "(J)I", peek as *mut c_void),
            method(
                "internalPathIteratorRawSize",
                "(J)I",
                raw_size as *mut c_void,
            ),
            method("internalPathIteratorSize", "(J)I", size as *mut c_void),
        ],
    )?;
    let converter = env.find_class(CONVERTER_CLASS)?;
    env.register_native_methods(
        &converter,
        &[method(
            "internalConicToQuadratics",
            "([FI[FFF)I",
            conic_to_quadratics_jni as *mut c_void,
        )],
    )
}

/// Same contract as the C++ `JNI_OnLoad`: register everything or fail loading.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let Ok(mut env) = vm.get_env() else {
        return JNI_ERR;
    };
    match register(&mut env) {
        Ok(()) => JNI_VERSION_1_6,
        Err(_) => JNI_ERR,
    }
}
