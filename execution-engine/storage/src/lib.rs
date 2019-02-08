extern crate common;
use common::key::Key;
use common::value::Value;
use common::bytesrepr::deserialize;

extern crate wasmi;
use self::wasmi::HostError;

extern crate rkv;                   // rust wrapper over LMDB (which includes LMDB itself)
use rkv::{ Manager, Rkv, Store };   // also use rkv::Value (ensure no conflict with common::value::Value above)

extern crate rand;
use rand::{ FromEntropy, RngCore };

use std::collections::{ BTreeMap, HashMap };
use std::{ fmt, mem::discriminant, path::Path };

use self::op::Op;
use self::transform::Transform;
use self::utils::add;

pub mod op;
pub mod transform;
mod utils;

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    KeyNotFound  { key: Key },
    TypeMismatch { expected: String, found: String },
    Rkv_error_StoreError   // rkv::Reader::get() or rkv::Writer::put() returned rkv::error::StoreError (use it's state?)
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:?}", self)
    }
}

impl HostError for Error {}

pub trait GlobalState<T: TrackingCopy> {
    fn apply(&mut self, k: Key, t: Transform) -> Result<(), Error>;
    fn get(&self, k: &Key) -> Result<&Value, Error>;
    fn tracking_copy( &self, preStateKey: Key ) -> T;
}

#[derive(Debug)]
pub struct ExecutionEffect(pub HashMap<Key, Op>, pub HashMap<Key, Transform>);

pub trait TrackingCopy {
    fn new_uref(&mut self, v: Value ) -> Key;   // initial value
    fn read(&mut self, k: Key) -> Result<&Value, Error>;
    fn write(&mut self, k: Key, v: Value) -> Result<(), Error>;
    fn add(&mut self, k: Key, v: Value) -> Result<(), Error>;
}

// !! LMDB creates 2 files in the specified directory: data.mdb and lock.mdb
// !! these files may not work when moved between 32-bit and 64-bit machines

pub struct LMDB< 'a > {
   path   : &'a Path,
   env    : std::sync::RwLockReadGuard<'a, rkv::Rkv>,   // implements Deref trait so the enclosed rkv::Rkv instance is accessible w/o additional work
   store  : Store,
   reader : rkv::Reader< 'a, &'a Key >   // this is an optimization so a reader does not need to be created on every read
}

impl< 'a > LMDB< 'a > {

   pub fn new( path : &str ) -> LMDB {
      let lmdbDir = Path::new( path );
      // singleton() ensures each process opens the same environment (LMDB files) at most once by caching a handle on open.
      // this returns RwLock<Manager> which allows any number of concurrent readers and at most one concurrent writer.
      // RwLock::write() blocks the thread until a write lock - the write lock is dropped when the RwLock instance is droppped.
      // not exactly sure why this is here but it's from their documentation.
      let handle = Manager::singleton().write().unwrap();
      // Manager::get_or_create() returns a handle to an opened environment or uses Rkv::new() to create one.
      let env = handle.get_or_create( lmdbDir, Rkv::new ).unwrap().read().unwrap();   // TODO: check and respond to errors on these unwrap()'s
      LMDB {
         path   : lmdbDir,
         env    : env,
         store  : env.open_or_create_default().unwrap(),  // returns handle to default (unnamed) store for the environment
         reader : env.read().expect( "reader" )   // all keys given to LMDB must implement AsRef<[u8]>
      }
   }
}

impl< 'a > GlobalState< Cache< 'a > > for LMDB< 'a > {
    //-------------------------------------------------------------------------------
    fn apply( &mut self, k : Key, t : Transform ) -> Result< (), Error > {  // TODO: why is a Transform being sent here rather than source data?

        match self.reader.get( self.store, &k ) {   // translate to blob to_bytes
            Ok( v ) => {
                let mut writer = self.env.write().unwrap();   // TODO: better error handling than unwrap()?
                match v {
                    Some( val ) => {                          // key does exist
                        writer.put( self.store, &k, t.apply( val )? );
                        writer.commit();                      // must commit writes before writer goes out of scope!
                        Ok( () )
                    },
                    None => match t {                         // key does not exist
                        Transform::Write => {
                            writer.put( self.store, &k, val );   // TODO: what does this do when key is absent?
                            writer.commit();
                            Ok( () )
                        },
                        _ => Err( Error::KeyNotFound { key: k } )
                    }
                }
            },
            Err( e ) => Err( Error::Rkv_error_StoreError )   // e is rkv::error::StoreError
        }
    }
    //-------------------------------------------------------------------------------
    fn get( &self, k : &Key ) -> Result< Value, Error > {
        match self.reader.get( self.store, k ) {   // returns Result< Option< rkv::Value::Blob >, rkv::error::StoreError >
            Ok( v1 ) => match v1 {
                            Some( v2 ) => Ok( deserialize( v2 ).unwrap() ),   // v2 is rkv::Value::Blob<[u8]>;
                            None       => Err( Error::KeyNotFound { key: *k } )
                        },
            Err( e ) => Err( Error::Rkv_error_StoreError )
        }
    }
    //-------------------------------------------------------------------------------
    fn tracking_copy( &self, preStateKey: Key ) -> Cache< 'a > {
        Cache< 'a > {
            preState    : self,
            preStateKey : preStateKey,
            store       : HashMap::new(),
            rng         : rand::rngs::StdRng::from_entropy()
        }
    }
    //-------------------------------------------------------------------------------
}

// a Cache instance is the context for each contract activation
pub struct Cache< 'a > {
    preState    : &'a LMDB< 'a >,   // pointer to global store GLobalState; accessed on a cache miss
    preStateKey : Key,    // identifies pre-state to use; prepended to all global store accesses
    store       : HashMap< Key, ( Value, Transform ) >,   // caches all reads and writes of state from contract
    rng         : rand::rngs::StdRng
}

impl< 'a > TrackingCopy for Cache< 'a > {
    //-------------------------------------------------------------------------------
    fn new_uref( &mut self, v: Value ) -> Key {   // create new uref and initialize to v
        let mut key = [ 0u8; 32 ];
        self.rng.fill_bytes( &mut key );
        let key = Key::URef( key );
        self.store.insert( key, ( v.clone(), Transform::Write ) );
        key
    }
    //-------------------------------------------------------------------------------
    fn read( &mut self, k: Key ) -> Result< &Value, Error > {

        match self.store.get( &k ) {
            Some( ( v1, _ ) ) => Ok( v1 ),   // Cache hit; read is most commutative = no need to update Op
            None => {                        // Cache miss; look in preState
                match self.preState.get( &k ) {
                    Ok( v2 ) => {            // in preState
                        self.store.insert( k, ( *v2, Transform::Read ) );
                        Ok( v2 )
                    },
                    Err( e ) => Err( e )     // not in preState or LMBD error
                }
            }
        }
    }
    //-------------------------------------------------------------------------------
    fn write( &mut self, k: Key, v: Value ) -> Result< (), Error > {

        let oldV = match self.store.get( &k ) {
            Some( ( v1, _ ) ) => Ok( v1 ),                         // Cache hit
            None              => match self.preState.get( &k ) {   // Cache miss; look in preState
                                     Ok( v2 ) => Ok( v2 ),         // in preState
                                     Err( e ) => Err( e )          // not in preState or LMDB error
                                 }
        };

        match oldV {
            Ok( oldV2 ) =>
                if discriminant( &v ) == discriminant( oldV2 ) {   // variant of new value == variant of old value
                    self.store.insert( k, ( v.clone(), Transform::Write ) );
                    Ok( () )
                } else {                                           // variant of new value != variant of old value
                    Err( Error::TypeMismatch {
                             expected: "no change of type on write".to_string(),
                             found:    format!( "write of {:?} over {:?}", v, oldV2 ) } )
                },
            Err( e2 ) => Err( e2 )
        }
     }
    //==============================================================
    fn add( &mut self, k: Key, v: Value ) -> Result<(), Error> {

        let old = match self.store.get( &k ) {                           // old is Result< ( &Value, &Transform ) >
            Some( ( oldVal, oldOp ) ) => Ok( ( oldVal, oldOp ) ),        // cache hit
            None =>                                                      // cache miss
                match self.preState.get( &k ) {
                    Ok( oldVal ) => Ok( ( oldVal, &Transform::Read ) ),  // in preState; Transform::Read makes newOp calc work below
                    Err( e )     => Err( e )                             // not in preState or LMDB error
                }
        };

        let inc = match ( v, old ) {                                               // inc is Result< Value >
            ( Value::Int32( _ ), Ok( ( Value::Int32( _ ), _ ) ) ) => Ok( v ),      // add of Int32 to Int32

            ( Value::NamedKey( name, key ), Ok( ( Value::Contract { bytes: _, known_urefs: _ }, _ ) ) ) |
            ( Value::NamedKey( name, key ), Ok( ( Value::Acct( _ ), _ ) ) ) => {   // add of NamedKey to either Contract or Acct
                let inc2 = BTreeMap::new();
                inc2.insert( name, key );
                Ok( Value::UrefMap( inc2 ) )                                       // create BTreeMap to make it easier to add to existing BTreeMap
            },

            ( otherInc, Ok( ( otherVal, _ ) ) ) =>
                Err( Error::TypeMismatch {
                         expected: "add Int32 to Int32, or add of NamedKey to either Contract or Acct".to_string(),
                         found:    format!( "add of {:?} to {:?}", otherInc, otherVal ) } ),

            ( _, Err( _ ) ) => old
        };

        match ( old, inc ) {
            ( Ok( ( oldVal, oldOp ) ), Ok( inc2 ) ) => {
                let newOp = match oldOp {
                    Transform::Write         => Transform::Write,
                    Transform::Add( oldInc ) => Transform::Add( oldInc + inc2 ),   // oldInc and inc are V::Int32 or V::UrefMap
                    _                        => Transform::Add( inc2 )
                };
                self.store.insert( k, ( oldVal + inc2, newOp ) );   // oldVal is V::Int32 or V::Contract or V::Acct
                Ok( () )
            },

            ( _, Err(_) ) => inc                                    // errors in old are propagated througn inc
        }
    }
    //-------------------------------------------------------------------------------
}
